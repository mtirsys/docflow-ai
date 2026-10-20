package docflow_backend.service;

import docflow_backend.exception.DocumentNotFoundException;
import docflow_backend.dto.DocumentPageResponse;
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
}
