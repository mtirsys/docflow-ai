package docflow_backend.service;

import docflow_backend.dto.DocumentPageResponse;
import docflow_backend.dto.UploadCheckResponse;
import docflow_backend.exception.DocumentNotFoundException;
import docflow_backend.mapper.DocumentMapper;
import docflow_backend.model.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

@Service
public class DocumentService {

    /** Day 13：允许上传的扩展名白名单，全部转成小写后再比较，所以 .PDF/.Pdf 也放行。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "md");

    /** Day 13：单文件上限 10 MB（10 * 1024 * 1024 字节）。 */
    private static final long MAX_UPLOAD_SIZE_BYTES = 10L * 1024 * 1024;

    private final DocumentMapper documentMapper;

    public DocumentService(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    public Long create(Document document) {
        int rows = documentMapper.insert(document);

        if (rows != 1 || document.getId() == null) {
            throw new IllegalStateException("新增文档失败");
        }

        return document.getId();
    }

    public List<Document> findAll() {
        return documentMapper.findAll();
    }

    /**
     * 校验分页边界后，再把 0 开始的页码换算成数据库需要跳过的记录数。
     */
    public DocumentPageResponse findPage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page 不能小于 0");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size 必须在 1 到 100 之间");
        }

        long offset = (long) page * size;
        List<Document> content = documentMapper.findPage(size, offset);
        long totalElements = documentMapper.count();
        long totalPages = totalElements / size
                + (totalElements % size == 0 ? 0 : 1);

        return new DocumentPageResponse(content, page, size, totalElements, totalPages);
    }

    /**
     * 查不到不再是"返回 null 让调用者猜"，而是直接声明事实：这条数据不存在。
     * 抛出的异常由 GlobalExceptionHandler 统一翻译成 404 响应。
     */
    public Document findById(Long id) {
        Document document = documentMapper.findById(id);
        if (document == null) {
            throw new DocumentNotFoundException(id);
        }
        return document;
    }

    /**
     * update/delete 以前靠 boolean 返回值让 Controller 判断 404，
     * 现在同样改为：影响行数为 0 就说明目标不存在，直接抛异常。
     */
    public void update(Long id, Document document) {
        String fileType = document.getFileType() == null
                ? "" : document.getFileType().trim();

        int rows = documentMapper.updateById(
                id,
                document.getTitle().trim(),
                fileType
        );

        if (rows == 0) {
            throw new DocumentNotFoundException(id);
        }
    }

    public void delete(Long id) {
        int rows = documentMapper.deleteById(id);
        if (rows == 0) {
            throw new DocumentNotFoundException(id);
        }
    }

    /**
     * Day 13：只验证上传文件"能不能收"，不保存、不写数据库。
     *
     * 校验失败的口径统一为 IllegalArgumentException，
     * 这样 GlobalExceptionHandler 里现成的"参数非法 → 400"翻译就能直接复用，
     * 不需要为上传再发明一套新的错误机制。
     *
     * 注意：这里没有把文件内容读进内存，也没有把文件名拼成任何路径——
     * 只看元数据（文件名、大小），这正是"验证接口"和"存储接口"的分界线。
     */
    public UploadCheckResponse checkUploadedFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空：请选择一个非空文件");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("缺少文件名：上传的文件没有名字");
        }

        // 旧版浏览器可能在文件名里夹带客户端路径（如 C:\docs\a.pdf），
        // 先剥掉斜杠前的部分，只用纯粹的文件名做判断和回显，绝不直接拼路径。
        String filename = extractFilename(originalName);

        String extension = extractExtension(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "不支持的文件类型：只允许 PDF、DOCX、MD，收到的是 ." + extension);
        }

        // 双保险：multipart 配置会在更外层拦截超大请求，
        // 但为了不依赖配置，业务层自己也卡一道 10 MB 的线。
        if (file.getSize() > MAX_UPLOAD_SIZE_BYTES) {
            throw new IllegalArgumentException("文件过大：单个文件不能超过 10 MB");
        }

        // getSize() 是服务器解析报文后数出来的字节数；getContentType() 是客户端自报的，
        // 两者可信度不同，所以响应里分开返回，让调用方自己判断。
        return new UploadCheckResponse(filename, file.getSize(), file.getContentType());
    }

    /**
     * 只保留最后一个 / 或 \ 之后的部分。
     * 例如 "C:\docs\报告.pdf" → "报告.pdf"。
     */
    private static String extractFilename(String originalName) {
        int slash = Math.max(originalName.lastIndexOf('/'), originalName.lastIndexOf('\\'));
        return slash >= 0 ? originalName.substring(slash + 1) : originalName;
    }

    /**
     * 取最后一个点之后的部分并转小写，统一口径比较。
     * 没有点、或点在结尾（如 "report."），都视为没有扩展名。
     */
    private static String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }
}
