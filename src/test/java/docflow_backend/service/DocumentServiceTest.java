package docflow_backend.service;

import docflow_backend.dto.DocumentPageResponse;
import docflow_backend.mapper.DocumentMapper;
import docflow_backend.model.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

    @ParameterizedTest(name = "总记录数 {0}、每页 {1} 条时共有 {2} 页")
    @CsvSource({"23, 5, 5", "20, 5, 4"})
    @DisplayName("总页数正确处理向上取整和整除边界")
    void findPageCalculatesTotalPagesAtBoundaries(long totalElements, int size, long expectedTotalPages) {
        given(documentMapper.findPage(size, 0L)).willReturn(List.of());
        given(documentMapper.count()).willReturn(totalElements);

        DocumentPageResponse response = documentService.findPage(0, size);

        assertEquals(0, response.page());
        assertEquals(size, response.size());
        assertEquals(totalElements, response.totalElements());
        assertEquals(expectedTotalPages, response.totalPages());
        verify(documentMapper).findPage(size, 0L);
    }

    @Test
    @DisplayName("最大 int 页码使用 long 计算 offset，避免 int 乘法溢出")
    void findPageCalculatesLargeOffsetAsLong() {
        long expectedOffset = 214_748_364_700L;
        given(documentMapper.findPage(100, expectedOffset)).willReturn(List.of());
        given(documentMapper.count()).willReturn(0L);

        DocumentPageResponse response = documentService.findPage(Integer.MAX_VALUE, 100);

        assertEquals(Integer.MAX_VALUE, response.page());
        verify(documentMapper).findPage(100, expectedOffset);
    }

    @Test
    @DisplayName("page 和 size 超出边界时不访问数据库")
    void findPageRejectsInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () -> documentService.findPage(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> documentService.findPage(0, 0));
        assertThrows(IllegalArgumentException.class, () -> documentService.findPage(0, 101));

        verifyNoInteractions(documentMapper);
    }
}
