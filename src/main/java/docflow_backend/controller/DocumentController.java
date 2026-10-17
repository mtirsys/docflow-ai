package docflow_backend.controller;

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
            @Valid @RequestBody Document document) {

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
            @Valid @RequestBody Document document) {

        if (!documentService.update(id, document)) {
            return notFound();
        }

        return ResponseEntity.ok(
                Map.of("message", "updated", "id", id)
        );
    }

    private ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Not Found", "message", "Document not found"));
    }
}
