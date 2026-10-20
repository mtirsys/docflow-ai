package docflow_backend.service;

import docflow_backend.dto.DocumentPageResponse;
import docflow_backend.mapper.DocumentMapper;
import docflow_backend.model.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DocumentServiceTest {

    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private final DocumentService documentService = new DocumentService(documentMapper);

    @Test
    @DisplayName("第二页每页五条：offset 为 5，总页数向上取整")
    void findPageCalculatesOffsetAndTotalPages() {
        List<Document> documents = List.of(new Document());
        given(documentMapper.findPage(5, 5L)).willReturn(documents);
        given(documentMapper.count()).willReturn(6L);

        DocumentPageResponse response = documentService.findPage(1, 5);

        assertEquals(documents, response.content());
        assertEquals(1, response.page());
        assertEquals(5, response.size());
        assertEquals(6L, response.totalElements());
        assertEquals(2L, response.totalPages());
        verify(documentMapper).findPage(5, 5L);
    }

    @Test
    @DisplayName("空表：返回零条记录和零页")
    void findPageReturnsZeroPagesForEmptyTable() {
        given(documentMapper.findPage(10, 0L)).willReturn(List.of());
        given(documentMapper.count()).willReturn(0L);

        DocumentPageResponse response = documentService.findPage(0, 10);

        assertEquals(List.of(), response.content());
        assertEquals(0L, response.totalElements());
        assertEquals(0L, response.totalPages());
    }

    @Test
    @DisplayName("page 和 size 超出边界时不访问数据库")
    void findPageRejectsInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () -> documentService.findPage(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> documentService.findPage(0, 0));
        assertThrows(IllegalArgumentException.class, () -> documentService.findPage(0, 101));
    }
}
