package docflow_backend.service;

import docflow_backend.dto.DocumentChunkResponse;
import docflow_backend.mapper.DocumentChunkMapper;
import docflow_backend.mapper.DocumentMapper;
import docflow_backend.model.Document;
import docflow_backend.model.DocumentChunk;
import docflow_backend.parser.TextChunker;
import docflow_backend.storage.LocalFileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Day 24：chunkAndSave 的单元测试（不起 Spring、不连数据库，同 DocumentServiceTest 风格）。
 *
 * 装配取舍：
 * - mock 的 DocumentMapper / DocumentChunkMapper 是"数据库边界外的邻居"——
 *   被测的是 Service 的编排逻辑（调用顺序、传参内容、失败传播），不是 SQL 本身；
 * - 真的 LocalFileStorageService + @TempDir + 真的 TextChunker / TextContentCleaner
 *   是"今天的主角"——分块必须真刀真枪地切，断言才有意义；
 * - 落库结果靠 ArgumentCaptor 捕获 insertBatch 收到的实体列表来检查：
 *   Mapper 是假的，但"交给它什么"是真实编排产物。
 *
 * 关于"事务回滚"的诚实说明：单元测试里没有 Spring 事务管理器，
 * @Transactional 在这里不生效。能测的是它的回滚前提——
 * "插入失败时异常原样抛出"（抛异常正是生产环境触发回滚的信号），
 * 以及"删除发生在插入之前"（保证了回滚时有一套完整旧块可以恢复）。
 * 真正的物理回滚验证需要真数据库 + Spring 上下文，超出本项目测试架构。
 */
class DocumentChunkServiceTest {

    @TempDir
    Path tempDir;

    private DocumentMapper documentMapper;
    private DocumentChunkMapper documentChunkMapper;
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        documentChunkMapper = mock(DocumentChunkMapper.class);
        documentService = new DocumentService(
                documentMapper, documentChunkMapper,
                new LocalFileStorageService(tempDir.toString()),
                new docflow_backend.parser.PdfTextExtractor(),
                new docflow_backend.parser.DocxTextExtractor(),
                new docflow_backend.parser.TextContentCleaner(),
                new TextChunker());
    }

    /**
     * 场景搭台：往临时目录写一个 Markdown 文件，让 mock 的 DocumentMapper
     * 对 findById(id) 返回指向它的 Document——与真实上传产生的形态一致。
     */
    private void seedDocument(long id, String storageKey, String content) throws IOException {
        Files.writeString(tempDir.resolve(storageKey), content, StandardCharsets.UTF_8);

        Document document = new Document();
        document.setId(id);
        document.setTitle(storageKey);
        document.setOriginalFilename(storageKey);
        document.setFileType("MD");
        document.setStorageKey(storageKey);
        document.setStatus("UPLOADED");
        given(documentMapper.findById(id)).willReturn(document);
    }

    private static String para(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    @Test
    @DisplayName("单块文档：切出 1 块，index 从 0 开始，内容与字符数对得上")
    void singleChunkDocumentSavesOneChunk() throws IOException {
        String storageKey = "11111111-2222-3333-4444-555555555555.md";
        seedDocument(15L, storageKey, para('甲', 100));
        given(documentChunkMapper.deleteByDocumentId(15L)).willReturn(0);
        given(documentChunkMapper.insertBatch(anyList())).willReturn(1);

        DocumentChunkResponse response = documentService.chunkAndSave(15L);

        assertEquals(15L, response.documentId());
        assertEquals(1, response.totalChunks());
        assertEquals(100, response.totalChars());

        ArgumentCaptor<List<DocumentChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkMapper).insertBatch(captor.capture());
        List<DocumentChunk> saved = captor.getValue();

        assertEquals(1, saved.size());
        assertEquals(0, saved.get(0).getChunkIndex());
        assertEquals(15L, saved.get(0).getDocumentId());
        assertEquals(para('甲', 100), saved.get(0).getContent());
        assertEquals(100, saved.get(0).getCharCount());
    }

    @Test
    @DisplayName("多块文档：900 字三段切出 3 块，顺序 0/1/2 连续，块块都盖对 documentId")
    void multiChunkDocumentSavesOrderedChunks() throws IOException {
        String storageKey = "22222222-3333-4444-5555-666666666666.md";
        String content = para('甲', 300) + "\n\n" + para('乙', 300) + "\n\n" + para('丙', 300);
        seedDocument(16L, storageKey, content);
        given(documentChunkMapper.deleteByDocumentId(16L)).willReturn(0);
        given(documentChunkMapper.insertBatch(anyList())).willReturn(3);

        DocumentChunkResponse response = documentService.chunkAndSave(16L);

        assertEquals(3, response.totalChunks());

        ArgumentCaptor<List<DocumentChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkMapper).insertBatch(captor.capture());
        List<DocumentChunk> saved = captor.getValue();

        assertEquals(3, saved.size());
        // 顺序正确：第 i 个元素就是第 i 块，编号从 0 连续递增
        for (int i = 0; i < saved.size(); i++) {
            assertEquals(i, saved.get(i).getChunkIndex());
            assertEquals(16L, saved.get(i).getDocumentId());
            assertEquals(saved.get(i).getContent().length(),
                    saved.get(i).getCharCount());
        }
        // 块大小都遵守 500 上限；第一块就是第一段；总字数一块不丢
        for (DocumentChunk chunk : saved) {
            assertTrue(chunk.getContent().length() <= 500);
        }
        assertEquals(para('甲', 300), saved.get(0).getContent());
        assertEquals(saved.stream().mapToLong(DocumentChunk::getCharCount).sum(),
                response.totalChars());
    }

    @Test
    @DisplayName("文档隔离：两个文档各自分块，各自批次里只有自己的 documentId 和内容")
    void chunksOfDifferentDocumentsDoNotMix() throws IOException {
        seedDocument(1L, "doc-one.md", para('甲', 100));
        seedDocument(2L, "doc-two.md", para('乙', 100));

        documentService.chunkAndSave(1L);
        documentService.chunkAndSave(2L);

        ArgumentCaptor<List<DocumentChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkMapper, times(2)).insertBatch(captor.capture());

        List<List<DocumentChunk>> batches = captor.getAllValues();
        assertEquals(2, batches.size());

        List<DocumentChunk> forDocOne = batches.get(0);
        List<DocumentChunk> forDocTwo = batches.get(1);
        assertEquals(1, forDocOne.size());
        assertEquals(1, forDocTwo.size());
        assertEquals(1L, forDocOne.get(0).getDocumentId());
        assertEquals(para('甲', 100), forDocOne.get(0).getContent());
        assertEquals(2L, forDocTwo.get(0).getDocumentId());
        assertEquals(para('乙', 100), forDocTwo.get(0).getContent());
    }

    @Test
    @DisplayName("重新生成：删两次插两次，且每次插入前都先删，不会留下重复旧块")
    void regeneratingReplacesOldChunksInsteadOfAccumulating() throws IOException {
        String storageKey = "regen.md";
        String content = para('甲', 100);
        seedDocument(17L, storageKey, content);

        documentService.chunkAndSave(17L);
        documentService.chunkAndSave(17L);

        // 两次调用 = 两次"先删后插"，缺任何一半都会造成旧块残留或半套新块
        verify(documentChunkMapper, times(2)).deleteByDocumentId(17L);
        verify(documentChunkMapper, times(2)).insertBatch(anyList());

        // 顺序：delete → insert → delete → insert（InOrder 跨两个 mock 校验）
        InOrder inOrder = inOrder(documentChunkMapper);
        inOrder.verify(documentChunkMapper).deleteByDocumentId(17L);
        inOrder.verify(documentChunkMapper).insertBatch(anyList());
        inOrder.verify(documentChunkMapper).deleteByDocumentId(17L);
        inOrder.verify(documentChunkMapper).insertBatch(anyList());

        // 第二次插入的块和第一次逐字段一致：同一文档同一内容，重复调用幂等。
        // 不能用 assertEquals(整个List)：DocumentChunk 是普通 class，没重写
        // equals（对比 TextChunk 是 record、天生有值语义 equals），
        // List 的整体比较会退化成对象地址比较，两次构建必然不等。
        ArgumentCaptor<List<DocumentChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkMapper, times(2)).insertBatch(captor.capture());
        List<DocumentChunk> first = captor.getAllValues().get(0);
        List<DocumentChunk> second = captor.getAllValues().get(1);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getChunkIndex(), second.get(i).getChunkIndex());
            assertEquals(first.get(i).getDocumentId(), second.get(i).getDocumentId());
            assertEquals(first.get(i).getContent(), second.get(i).getContent());
            assertEquals(first.get(i).getCharCount(), second.get(i).getCharCount());
        }
    }

    @Test
    @DisplayName("插入失败：异常原样抛出（生产环境这正是触发事务回滚的信号）")
    void insertFailurePropagatesSoTransactionCanRollBack() throws IOException {
        seedDocument(18L, "fail.md", para('甲', 100));
        willThrow(new RuntimeException("connection lost"))
                .given(documentChunkMapper).insertBatch(anyList());

        assertThrows(RuntimeException.class, () -> documentService.chunkAndSave(18L));

        // 删除已发生在插入之前：生产环境回滚后，完整的一套旧块会被恢复，
        // 库里不会停留在"删了旧的、新的没插进去"的半套状态。
        verify(documentChunkMapper).deleteByDocumentId(18L);
        verify(documentChunkMapper).insertBatch(anyList());
    }

    @Test
    @DisplayName("空文档：切出 0 块是合法结果，只删旧块、不拼残缺 SQL")
    void emptyDocumentSavesZeroChunksWithoutInvalidSql() throws IOException {
        seedDocument(19L, "empty.md", "");

        DocumentChunkResponse response = documentService.chunkAndSave(19L);

        assertEquals(0, response.totalChunks());
        assertEquals(0L, response.totalChars());
        // 删照常执行（清掉历史旧块），但 insertBatch 绝不能被调——
        // foreach 拼空列表会生成 "VALUES" 后没有内容的残缺 SQL
        verify(documentChunkMapper).deleteByDocumentId(19L);
        verify(documentChunkMapper, never()).insertBatch(any());
    }
}
