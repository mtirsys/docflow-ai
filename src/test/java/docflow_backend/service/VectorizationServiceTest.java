package docflow_backend.service;

import docflow_backend.dto.VectorizationResult;
import docflow_backend.exception.DocumentNotFoundException;
import docflow_backend.mapper.DocumentChunkMapper;
import docflow_backend.model.Document;
import docflow_backend.model.DocumentChunk;
import docflow_backend.service.QdrantVectorStoreService.ChunkVector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Day 28：VectorizationService（向量化编排）的单元测试。
 *
 * 四个依赖全部 mock（DocumentService / DocumentChunkMapper / EmbeddingService /
 * QdrantVectorStoreService），不联网、不连库。被测的是"编排"本身：
 * 校验文档存在 → 取块 → 确保集合 → 逐块嵌入 → 整批落库 → 回执统计。
 *
 * 重点验证两个设计决策：
 * 1. "有任一块失败就整批不落库"——与 Day 27 batchEmbed 的"允许部分失败"
 *    刻意相反，因为落库半批的代价是检索质量悄悄劣化（见类注释）；
 * 2. "现有类零修改"——DocumentService 不出现在构造参数里不是省事，
 *    是复用它的 findById 拿 404 语义。
 */
class VectorizationServiceTest {

    private DocumentService documentService;
    private DocumentChunkMapper documentChunkMapper;
    private EmbeddingService embeddingService;
    private QdrantVectorStoreService qdrantVectorStoreService;
    private VectorizationService vectorizationService;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        documentChunkMapper = mock(DocumentChunkMapper.class);
        embeddingService = mock(EmbeddingService.class);
        qdrantVectorStoreService = mock(QdrantVectorStoreService.class);
        vectorizationService = new VectorizationService(
                documentService, documentChunkMapper, embeddingService, qdrantVectorStoreService);
    }

    /** 文档存在（findById 不抛即视为存在，Document 由 DocumentService 负责）。 */
    private void seedDocumentExists(long id) {
        Document doc = new Document();
        doc.setId(id);
        when(documentService.findById(id)).thenReturn(doc);
    }

    /** 造一个带主键的 chunk 实体——chunkId 进 Point ID，必须有真实值。 */
    private DocumentChunk chunk(long id, int index, String content) {
        DocumentChunk c = new DocumentChunk();
        c.setId(id);
        c.setDocumentId(9L);
        c.setChunkIndex(index);
        c.setContent(content);
        c.setCharCount(content.length());
        return c;
    }

    private List<Float> fakeVector() {
        Float[] v = new Float[1024];
        java.util.Arrays.fill(v, 0.5f);
        return List.of(v);
    }

    @Test
    @DisplayName("多块文档全成功：逐块嵌入后整批落库，ChunkVector 的 ID/序号逐项正确")
    void multiChunkSuccessStoresAll() {
        long id = 9L;
        seedDocumentExists(id);
        List<DocumentChunk> chunks = List.of(
                chunk(101L, 0, "第一块"),
                chunk(102L, 1, "第二块"),
                chunk(103L, 2, "第三块"));
        when(documentChunkMapper.findByDocumentId(id)).thenReturn(chunks);
        when(embeddingService.embed(anyString())).thenReturn(fakeVector());
        when(qdrantVectorStoreService.storeDocumentVectors(anyLong(), any()))
                .thenReturn(3);

        VectorizationResult result = vectorizationService.embedAndStore(id);

        assertEquals(id, result.documentId());
        assertEquals(3, result.totalChunks());
        assertEquals(3, result.embeddedCount());
        assertEquals(0, result.failedCount());
        assertEquals(3, result.storedPoints());
        assertEquals(1024, result.dimensions());
        assertTrue(result.failures().isEmpty());

        // 交给存储层的内容逐项体检：chunkId 进 ChunkVector、chunkIndex 保序
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChunkVector>> captor =
                ArgumentCaptor.forClass((Class<List<ChunkVector>>) (Class<?>) List.class);
        verify(qdrantVectorStoreService).storeDocumentVectors(anyLong(), captor.capture());
        List<ChunkVector> stored = captor.getValue();
        assertEquals(3, stored.size());
        assertEquals(101L, stored.get(0).chunkId());
        assertEquals(103L, stored.get(2).chunkId());
        assertEquals(2, stored.get(2).chunkIndex());
        assertEquals(1024, stored.get(0).vector().size());

        // 顺序：先 ensureCollection（库不对就不烧 API 额度），再逐块 embed，最后落库
        InOrder order = inOrder(qdrantVectorStoreService, embeddingService);
        order.verify(qdrantVectorStoreService).ensureCollection();
        order.verify(embeddingService, times(3)).embed(anyString());
        order.verify(qdrantVectorStoreService).storeDocumentVectors(anyLong(), any());
    }

    @Test
    @DisplayName("文档不存在：404 通道直接透传，不查块、不碰 Qdrant")
    void missingDocumentPropagates404AndStops() {
        when(documentService.findById(999L)).thenThrow(new DocumentNotFoundException(999L));

        assertThrows(DocumentNotFoundException.class,
                () -> vectorizationService.embedAndStore(999L));

        verify(documentChunkMapper, never()).findByDocumentId(anyLong());
        verify(qdrantVectorStoreService, never()).ensureCollection();
        verify(embeddingService, never()).embed(anyString());
    }

    @Test
    @DisplayName("文档没有块：合法业务结果，只清理旧向量，回执全零")
    void documentWithoutChunksOnlyCleans() {
        long id = 10L;
        seedDocumentExists(id);
        when(documentChunkMapper.findByDocumentId(id)).thenReturn(List.of());
        when(qdrantVectorStoreService.storeDocumentVectors(anyLong(), any())).thenReturn(0);

        VectorizationResult result = vectorizationService.embedAndStore(id);

        assertEquals(0, result.totalChunks());
        assertEquals(0, result.embeddedCount());
        assertEquals(0, result.storedPoints());
        verify(embeddingService, never()).embed(anyString());
        // 空文档也走"先删后写"路径（清掉该文档可能的旧向量）
        verify(qdrantVectorStoreService).storeDocumentVectors(eqLong(id), any());
    }

    @Test
    @DisplayName("部分块嵌入失败：本次整批不落库（存储层零调用），失败明细如实报告")
    void partialEmbedFailureAbortsWholeStore() {
        long id = 11L;
        seedDocumentExists(id);
        List<DocumentChunk> chunks = List.of(
                chunk(201L, 0, "正常块"),
                chunk(202L, 1, "会失败块"));
        when(documentChunkMapper.findByDocumentId(id)).thenReturn(chunks);
        when(embeddingService.embed(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if ("会失败块".equals(text)) {
                throw new RuntimeException("模拟：百炼限流");
            }
            return fakeVector();
        });

        VectorizationResult result = vectorizationService.embedAndStore(id);

        assertEquals(2, result.totalChunks());
        assertEquals(1, result.embeddedCount());
        assertEquals(1, result.failedCount());
        assertEquals(0, result.storedPoints()); // 关键：没写进 Qdrant
        assertEquals(1, result.failures().size());
        assertEquals(1, result.failures().get(0).chunkIndex());
        assertTrue(result.failures().get(0).reason().contains("限流"));

        verify(qdrantVectorStoreService, never()).storeDocumentVectors(anyLong(), any());
    }

    @Test
    @DisplayName("全部块嵌入失败：同样不落库，回执全零 + 全部失败明细")
    void allEmbedFailuresAbortsWholeStore() {
        long id = 12L;
        seedDocumentExists(id);
        when(documentChunkMapper.findByDocumentId(id))
                .thenReturn(List.of(chunk(301L, 0, "块A"), chunk(302L, 1, "块B")));
        when(embeddingService.embed(anyString()))
                .thenThrow(new RuntimeException("百炼 API 不可用"));

        VectorizationResult result = vectorizationService.embedAndStore(id);

        assertEquals(2, result.totalChunks());
        assertEquals(0, result.embeddedCount());
        assertEquals(2, result.failedCount());
        assertEquals(0, result.storedPoints());
        assertEquals(0, result.dimensions());
        assertEquals(2, result.failures().size());
        verify(qdrantVectorStoreService, never()).storeDocumentVectors(anyLong(), any());
    }

    @Test
    @DisplayName("重复执行：编排无状态，两次结果一致（幂等由存储层的先删后写保证）")
    void repeatedExecutionIsConsistent() {
        long id = 13L;
        seedDocumentExists(id);
        when(documentChunkMapper.findByDocumentId(id))
                .thenReturn(List.of(chunk(401L, 0, "唯一一块")));
        when(embeddingService.embed(anyString())).thenReturn(fakeVector());
        when(qdrantVectorStoreService.storeDocumentVectors(anyLong(), any())).thenReturn(1);

        VectorizationResult first = vectorizationService.embedAndStore(id);
        VectorizationResult second = vectorizationService.embedAndStore(id);

        assertEquals(first.totalChunks(), second.totalChunks());
        assertEquals(first.storedPoints(), second.storedPoints());
        // 两次都各调一次落库（先删后写在存储层内消化掉了重复）
        verify(qdrantVectorStoreService, times(2)).storeDocumentVectors(anyLong(), any());
    }

    /** eq(Long) 的小帮手：storeDocumentVectors 的第一个参数是 Long。 */
    private static long eqLong(long value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
