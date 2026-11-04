package docflow_backend.service;

import docflow_backend.dto.DocumentPageResponse;
import docflow_backend.exception.DocumentNotFoundException;
import docflow_backend.exception.FileStorageException;
import docflow_backend.mapper.DocumentMapper;
import docflow_backend.model.Document;
import docflow_backend.storage.LocalFileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DocumentServiceTest {

    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private final DocumentService documentService = new DocumentService(
            documentMapper, mock(LocalFileStorageService.class));

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

    @Test
    @DisplayName("删除 id 不存在：抛 DocumentNotFoundException，不调文件存储、不调 deleteById")
    void deleteNonExistentIdThrowsAndTouchesNothing() {
        // delete 测试需要让文件存储 mock 按剧本回答，所以每个用例单独装配
        LocalFileStorageService fileStorage = mock(LocalFileStorageService.class);
        DocumentService service = new DocumentService(documentMapper, fileStorage);
        Long id = 999L;

        // findById 返回 null → Service 内部 findById() 抛 DocumentNotFoundException
        given(documentMapper.findById(id)).willReturn(null);

        assertThrows(DocumentNotFoundException.class, () -> service.delete(id));

        // 文件存储和 DB 删除都不该被碰到
        verifyNoInteractions(fileStorage);
        verify(documentMapper, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("文件删除失败：抛 FileStorageException，DB 未动、记录仍在、可安全重试")
    void deleteWhenFileDeleteFailsLeavesDbUntouched() {
        LocalFileStorageService fileStorage = mock(LocalFileStorageService.class);
        DocumentService service = new DocumentService(documentMapper, fileStorage);
        Long id = 15L;
        String storageKey = "11111111-2222-3333-4444-555555555555.pdf";

        given(documentMapper.findById(id)).willReturn(documentWith(id, storageKey));
        given(fileStorage.delete(storageKey)).willReturn(false);  // 真没删成

        assertThrows(FileStorageException.class, () -> service.delete(id));

        // 关键断言：DB 一行没删（顺序 A 的核心保证）
        verify(documentMapper, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("数据库记录在但文件已被人手动删：delete 返回 true，正常继续删 DB，状态一致")
    void deleteWhenFileAlreadyMissingContinuesAndDeletesDb() {
        LocalFileStorageService fileStorage = mock(LocalFileStorageService.class);
        DocumentService service = new DocumentService(documentMapper, fileStorage);
        Long id = 15L;
        String storageKey = "11111111-2222-3333-4444-555555555555.pdf";

        given(documentMapper.findById(id)).willReturn(documentWith(id, storageKey));
        // Files.deleteIfExists 对"文件不存在"返回 true → 我们的 delete 也返回 true（幂等）
        given(fileStorage.delete(storageKey)).willReturn(true);
        given(documentMapper.deleteById(id)).willReturn(1);

        service.delete(id);

        verify(fileStorage).delete(storageKey);
        verify(documentMapper).deleteById(id);
    }

    @Test
    @DisplayName("文件已删、DB 抛异常：原样抛出，留下\"记录在文件丢\"由 download 404 兜底")
    void deleteWhenDbThrowsAfterFileDeletedRethrows() {
        LocalFileStorageService fileStorage = mock(LocalFileStorageService.class);
        DocumentService service = new DocumentService(documentMapper, fileStorage);
        Long id = 15L;
        String storageKey = "11111111-2222-3333-4444-555555555555.pdf";

        given(documentMapper.findById(id)).willReturn(documentWith(id, storageKey));
        given(fileStorage.delete(storageKey)).willReturn(true);
        willThrow(new RuntimeException("connection lost"))
                .given(documentMapper).deleteById(id);

        assertThrows(RuntimeException.class, () -> service.delete(id));
    }

    @Test
    @DisplayName("文件已删、deleteById 返回 0（并发删除）：抛 DocumentNotFoundException→404")
    void deleteWhenConcurrentDeleteReturnsZeroThrowsNotFound() {
        LocalFileStorageService fileStorage = mock(LocalFileStorageService.class);
        DocumentService service = new DocumentService(documentMapper, fileStorage);
        Long id = 15L;
        String storageKey = "11111111-2222-3333-4444-555555555555.pdf";

        given(documentMapper.findById(id)).willReturn(documentWith(id, storageKey));
        given(fileStorage.delete(storageKey)).willReturn(true);
        given(documentMapper.deleteById(id)).willReturn(0);

        assertThrows(DocumentNotFoundException.class, () -> service.delete(id));
    }

    /** 造一个带 storageKey 的 Document，delete 测试里反复用。 */
    private Document documentWith(Long id, String storageKey) {
        Document document = new Document();
        document.setId(id);
        document.setTitle("report.pdf");
        document.setStorageKey(storageKey);
        document.setOriginalFilename("report.pdf");
        document.setStatus("UPLOADED");
        return document;
    }
}
