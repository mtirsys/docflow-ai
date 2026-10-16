package docflow_backend.controller;

import docflow_backend.mapper.DocumentMapper;
import docflow_backend.model.Document;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentMapper documentMapper;

    public DocumentController(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody Document document) {

        int rows = documentMapper.insert(document);

        if (rows != 1 || document.getId() == null) {
            throw new IllegalStateException("新增文档失败");
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                        "message", "created",
                        "id", document.getId()
                ));
    }

    @GetMapping
    public List<Document> findAll() {
        return documentMapper.findAll();
    }
    @GetMapping("/{id}")
    public ResponseEntity<?> findById(
            @PathVariable Long id) {

        Document document = documentMapper.findById(id);

        if (document == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Not Found", "message", "Document not found"));
        }

        return ResponseEntity.ok(document);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        int rows = documentMapper.deleteById(id);
        if (rows == 0) {
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

        String fileType = document.getFileType() == null
                ? "" : document.getFileType().trim();

        int rows = documentMapper.updateById(
                id,
                document.getTitle().trim(),
                fileType
        );
        if (rows == 0) {
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
