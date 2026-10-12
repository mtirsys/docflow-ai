package docflow_backend.controller;

import docflow_backend.mapper.DocumentMapper;
import docflow_backend.model.Document;
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
            @RequestBody Document document) {

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
}