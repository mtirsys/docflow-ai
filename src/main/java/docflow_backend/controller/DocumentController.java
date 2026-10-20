package docflow_backend.controller;

import docflow_backend.dto.DocumentRequest;
import docflow_backend.dto.DocumentPageResponse;
import docflow_backend.model.Document;
import docflow_backend.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
