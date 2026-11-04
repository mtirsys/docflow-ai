package docflow_backend.storage;

import docflow_backend.exception.FileStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
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

    /**
     * Day 19：文本读取上限，与上传白名单的 10 MB 口径一致。
     * 上传时已限过一次，这里是防御第二道：不信任数据库里的历史数据。
     */
    private static final long MAX_TEXT_READ_BYTES = 10L * 1024 * 1024;

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
     * Day 17：按 storageKey 取回文件，交给上层作为下载响应的内容。
     *
     * 返回 Resource（Spring 对"可读字节流"的抽象）而不是 byte[] 或 Path：
     * - byte[] 会把整个文件一次性读进内存，大文件 + 并发下载会撑爆内存；
     * - Resource 交给 ResponseEntity 后由 Tomcat 边读边发（流式传输）。
     *
     * 越界检查和 store() 一样是双保险，但方向相反：写入防"存到外面去"，
     * 读取防"从外面偷读"——本次 storageKey 来自数据库，不能默认它干净。
     *
     * 文件不存在抛受检异常 FileNotFoundException：这是"预期中可能发生的业务状况"
     * （数据库有记录、磁盘文件丢了），编译器强制 DocumentService 当面处理并翻译，
     * 而不是像 FileStorageException 那样直接惊动全局兜底的 500。
     *
     * @throws FileStorageException storageKey 越界（疑似数据库被篡改）
     * @throws FileNotFoundException 文件不存在或不可读
     */
    public Resource load(String storageKey) throws FileNotFoundException {
        Path target = storageDir.resolve(storageKey).normalize();
        if (!target.startsWith(storageDir) || target.equals(storageDir)) {
            throw new FileStorageException("存储路径越界，已拒绝读取");
        }

        // isReadable 同时覆盖"不存在"和"存在但读不了"（如权限被改）两种情况。
        if (!Files.isReadable(target)) {
            // 只把 storageKey 写进异常（它本来就是服务器生成的 UUID，非敏感信息），
            // 绝不把磁盘绝对路径带给上层，更不会出现在 HTTP 响应里。
            throw new FileNotFoundException("存储文件不存在或不可读：" + storageKey);
        }

        // Path → URI → UrlResource 是官方标准写法。构造器声明了受检异常 MalformedURLException，
        // 但 Path.toUri() 生成的 file: URI 实际不会触发它；真触发了也属于服务器内部异常，
        // 包装成 FileStorageException 走 500 通道，和 store() 处理 IOException 的手法一致。
        try {
            return new UrlResource(target.toUri());
        } catch (MalformedURLException e) {
            log.error("文件读取时 URI 转换失败：{}", storageKey, e);
            throw new FileStorageException("文件读取失败", e);
        }
    }

    /**
     * Day 19：把 storageKey 对应的文件按 UTF-8 读成文本，供内容读取接口使用。
     *
     * 和 load() 的分工：load() 返回 Resource（字节流），服务"把原始文件发给浏览器"的下载；
     * 这里返回 String（文本），服务"把内容读进 JSON"的接口。
     *
     * 越界检查、存在性检查和 load() 完全同款：本类所有"从磁盘读"的方法
     * 都走同一套三段式（定位→验权→读取），安全问题只需要审计一个模式。
     *
     * 必须显式指定 UTF-8：不传字符集时 Files.readString 会用操作系统默认编码
     * （Windows 中文系统是 GBK），同一份 UTF-8 文件在不同机器上读出不同结果。
     *
     * 读取前先检查文件大小：上传白名单已限 10 MB，但这里不信任历史数据——
     * 数据库被篡改或早期写入的超大文件，不能被无限制灌进内存。
     *
     * @throws FileStorageException storageKey 越界、文件超大或读取时 I/O 失败（→ 500）
     * @throws FileNotFoundException 文件不存在或不可读（由调用方翻译成 404）
     */
    public String readText(String storageKey) throws FileNotFoundException {
        Path target = storageDir.resolve(storageKey).normalize();
        if (!target.startsWith(storageDir) || target.equals(storageDir)) {
            throw new FileStorageException("存储路径越界，已拒绝读取");
        }

        if (!Files.isReadable(target)) {
            throw new FileNotFoundException("存储文件不存在或不可读：" + storageKey);
        }

        try {
            long size = Files.size(target);
            if (size > MAX_TEXT_READ_BYTES) {
                log.error("文件超出文本读取上限，已拒绝读取：{}（{} 字节）", storageKey, size);
                throw new FileStorageException("文件过大，拒绝读取内容");
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("文件内容读取失败：{}", storageKey, e);
            throw new FileStorageException("文件内容读取失败", e);
        }
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
