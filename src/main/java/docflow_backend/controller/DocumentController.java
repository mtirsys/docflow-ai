package docflow_backend.controller;

import docflow_backend.dto.DocumentRequest;
import docflow_backend.dto.DocumentPageResponse;
import docflow_backend.dto.UploadCheckResponse;
import docflow_backend.model.Document;
import docflow_backend.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
