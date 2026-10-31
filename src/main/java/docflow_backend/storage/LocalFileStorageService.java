package docflow_backend.storage;

import docflow_backend.exception.FileStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Day 15：本地文件存储服务。整个项目里只有这个类真正碰磁盘。
 *
 * 和 DocumentService 的分工：
 * - DocumentService.checkUploadedFile() 负责"这个文件能不能收"（纯校验，不碰磁盘）；
 * - 这里负责"收下来之后放哪、叫什么名字"（只负责写盘，不做业务校验）。
 * Controller 把两步串起来：先校验，再存储。任何一步失败都会抛异常，由全局异常处理翻译。
 *
 * 存储名由服务器生成（UUID + 白名单内的扩展名），用户上传的文件名只用于回显，
 * 绝不参与磁盘路径的构造，所以"../../evil.exe"这类名字没有可乘之机。
 */
@Service
public class LocalFileStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageService.class);

    private final Path storageDir;

    /**
     * 存储目录从配置 docflow.storage.dir 读取，默认 D:/docflow-storage（在源码目录之外）。
     * 启动时就转成绝对路径并规范化，后续所有写入都以它为根。
     */
    public LocalFileStorageService(
            @Value("${docflow.storage.dir:D:/docflow-storage}") String storageDir) {
        this.storageDir = Paths.get(storageDir).toAbsolutePath().normalize();
    }

    /**
     * 把上传内容写入存储目录，返回生成的 storageKey（就是目录下的文件名）。
     *
     * 不覆盖任何已有文件：Files.copy 默认遇到同名文件会报错，
     * 而 UUID 撞名的概率可以忽略，所以正常情况永远是一笔"新增"。
     *
     * @throws FileStorageException 目录不可写、磁盘满等任何 I/O 失败（由调用方翻译成 500）
     */
    public String store(MultipartFile file) {
        String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
        String suffix = (extension == null || extension.isBlank())
                ? "" : "." + extension.toLowerCase();
        String storageKey = UUID.randomUUID() + suffix;

        // 双保险：名字虽然是服务器生成的，仍然验证最终路径没有逃出存储根目录。
        Path target = storageDir.resolve(storageKey).normalize();
        if (!target.startsWith(storageDir)) {
            throw new FileStorageException("存储路径越界，已拒绝写入");
        }

        try {
            // 目录不存在就创建（已存在则什么都不做），第一次上传前不需要手工建目录。
            Files.createDirectories(storageDir);
            // 用内容流写入，不一次性把文件读进内存；不打印、不查看文件内容。
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target);
            }
        } catch (IOException e) {
            // 原始异常（含磁盘路径、权限细节）只进日志，不进 HTTP 响应。
            log.error("文件写入存储目录失败：{}", storageDir, e);
            throw new FileStorageException("文件保存失败", e);
        }

        return storageKey;
    }

    /**
     * 只删除本次服务生成并返回的 storageKey 对应文件，用于"文件已写入、数据库却失败"
     * 的补偿。普通磁盘文件不参与 MySQL 事务，必须显式处理。
     *
     * @return true 表示文件已删除或本来就不存在；false 表示未能安全删除，详细原因已记日志
     */
    public boolean delete(String storageKey) {
        Path target = storageDir.resolve(storageKey).normalize();
        if (!target.startsWith(storageDir) || target.equals(storageDir)) {
            log.error("拒绝清理越界的 storageKey：{}", storageKey);
            return false;
        }

        try {
            return Files.deleteIfExists(target) || !Files.exists(target);
        } catch (IOException e) {
            log.error("数据库写入失败后的文件清理失败：{}", target, e);
            return false;
        }
    }
}
