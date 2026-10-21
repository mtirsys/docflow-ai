package docflow_backend.dto;

import docflow_backend.model.Document;

import java.util.List;

/**
 * 文档列表的分页响应。它只描述 API 返回的数据，不包含 SQL 或数据库访问逻辑。
 */
public record DocumentPageResponse(
        List<Document> content,
        int page,
        int size,
        long totalElements,
        long totalPages
) {
}
