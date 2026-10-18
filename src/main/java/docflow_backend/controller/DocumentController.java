package docflow_backend.controller;

import docflow_backend.dto.DocumentRequest;
import docflow_backend.model.Document;
import docflow_backend.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    public List<Document> findAll() {
        return documentService.findAll();
    }
    @GetMapping("/{id}")
    public ResponseEntity<?> findById(
            @PathVariable Long id) {

        Document document = documentService.findById(id);

        if (document == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Not Found", "message", "Document not found"));
        }

        return ResponseEntity.ok(document);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!documentService.delete(id)) {
            return notFound();
        }

        return ResponseEntity.ok(
                    Map.of("message", "deleted", "id", id)
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @Valid @RequestBody DocumentRequest request) {

        Document document = toDocument(request);
        if (!documentService.update(id, document)) {
            return notFound();
        }

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

    private ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Not Found", "message", "Document not found"));
    }
}
