package docflow_backend.service;

import docflow_backend.mapper.DocumentMapper;
import docflow_backend.model.Document;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DocumentService {

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

    public Document findById(Long id) {
        return documentMapper.findById(id);
    }

    public boolean update(Long id, Document document) {
        String fileType = document.getFileType() == null
                ? "" : document.getFileType().trim();

        return documentMapper.updateById(
                id,
                document.getTitle().trim(),
                fileType
        ) > 0;
    }

    public boolean delete(Long id) {
        return documentMapper.deleteById(id) > 0;
    }
}
