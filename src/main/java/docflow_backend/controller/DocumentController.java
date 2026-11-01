package docflow_backend.controller;

import docflow_backend.dto.DocumentDownload;
import docflow_backend.dto.DocumentRequest;
import docflow_backend.dto.DocumentPageResponse;
import docflow_backend.dto.UploadCheckResponse;
import docflow_backend.dto.UploadResponse;
import docflow_backend.model.Document;
import docflow_backend.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Day 10 之后，Controller 里不再出现任何"查不到就手写 404"的分支：
 * - 文档不存在：Service 抛 DocumentNotFoundException，GlobalExceptionHandler 统一返回 404；
 * - 参数校验失败：@Valid 触发 MethodArgumentNotValidException，统一返回 400；
 * - 其他意外：统一返回 500（不泄露内部信息）。
 * Controller 因此只剩"接收请求 + 调用业务 + 包装成功响应"三件事。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody DocumentRequest request) {

        Document document = toDocument(request);
        Long id = documentService.create(document);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                        "message", "created",
                        "id", id
                ));
    }

    /**
     * Day 13：基础文件上传验证接口。
     *
     * 和 create() 的关键区别：请求不是 JSON，而是 multipart/form-data（表单），
     * 所以参数上没有 @RequestBody，改用 @RequestParam("file") 从表单里取名为 file 的文件字段。
     * MultipartFile 就是 Spring 给这个字段的包装：文件名、大小、Content-Type、内容流都在里面。
     *
     * Controller 依旧只做三件事：接收请求 → 调用业务 → 包装成功响应。
     * 所有"什么样的文件算合法"的判断都在 DocumentService 里，
     * 失败时抛 IllegalArgumentException，由 GlobalExceptionHandler 统一翻译成 400。
     */
    @PostMapping("/upload-check")
    public ResponseEntity<UploadCheckResponse> uploadCheck(
            @RequestParam("file") MultipartFile file) {

        UploadCheckResponse result = documentService.checkUploadedFile(file);

        return ResponseEntity.ok(result);
    }

    /**
     * Day 16：完整上传由 Service 统一协调：校验、实际落盘、元数据入库及失败补偿。
     */
    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(
            @RequestParam("file") MultipartFile file) {

        return ResponseEntity.status(HttpStatus.CREATED).body(documentService.upload(file));
    }

    /**
     * Day 17：文件下载，和 upload() 是一对镜像——upload 收文件，download 发文件。
     *
     * 返回类型必须是 ResponseEntity<Resource>，响应体只能装文件的原始字节：
     * - 响应体 = Resource（文件内容本身，由 Tomcat 边读边发，不进内存堆）；
     * - 响应头负责"教浏览器怎么对待这坨字节"：
     *   Content-Type 说明"是什么"（如 application/pdf）；
     *   Content-Disposition: attachment 说明"存下来，别当网页显示"，并带上原始文件名。
     *
     * 两个"来源选择"：
     * - Content-Type 不用数据库的 file_type（大写的"PDF"是我们自己的显示分类，
     *   不是浏览器认识的 MIME 类型），而是按 originalFilename 的扩展名查
     *   Spring 内置 MIME 表（MediaTypeFactory，随 jar 走，任何机器结果一致），
     *   查不到兜底为 application/octet-stream（浏览器对它的标准反应就是下载）；
     * - 文件名不碰磁盘路径：originalFilename 只进响应头给浏览器看，
     *   磁盘定位早在 Service 里由 storageKey 完成了。
     *
     * 路由说明：/{id} 只匹配一段路径（/15），/{id}/download 是两段（/15/download），
     * Spring 按段数精确匹配，互不干扰。
     *
     * 失败路径全部交给既有机制：文档不存在或文件丢失 → Service 抛
     * DocumentNotFoundException → GlobalExceptionHandler → 404，本方法零分支。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        DocumentDownload download = documentService.download(id);

        MediaType contentType = MediaTypeFactory
                .getMediaType(download.originalFilename())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        // filename(name, charset) 重载：ASCII 名字输出 filename="report.pdf"，
        // 中文名字（如 季度报告.pdf）自动按 RFC 5987 编码成 filename*=UTF-8''...，
        // 浏览器都认识。手工拼这个头容易拼错引号和编码，交给构造器。
        ContentDisposition disposition = ContentDisposition
                .attachment()
                .filename(download.originalFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(download.resource());
    }

    @GetMapping
    public DocumentPageResponse findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        return documentService.findPage(page, size);
    }

    @GetMapping("/{id}")
    public Document findById(
            @PathVariable Long id) {

        return documentService.findById(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        documentService.delete(id);

        return ResponseEntity.ok(
                Map.of("message", "deleted", "id", id)
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @Valid @RequestBody DocumentRequest request) {

        documentService.update(id, toDocument(request));

        return ResponseEntity.ok(
                Map.of("message", "updated", "id", id)
        );
    }

    /**
     * 把请求 DTO 转换为业务层使用的 Document 实体。
     * 只搬 title 和 fileType，id、status、createdAt 始终由服务端掌控。
     */
    private Document toDocument(DocumentRequest request) {
        Document document = new Document();
        document.setTitle(request.getTitle());
        document.setFileType(request.getFileType());
        return document;
    }
}
