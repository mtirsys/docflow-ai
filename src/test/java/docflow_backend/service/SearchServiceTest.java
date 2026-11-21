package docflow_backend.service;

import docflow_backend.dto.SearchResult;
import docflow_backend.exception.QdrantStorageException;
import docflow_backend.mapper.DocumentChunkMapper;
import docflow_backend.model.DocumentChunk;
import docflow_backend.service.QdrantVectorStoreService.ScoredVector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Day 29：SearchService（检索编排）的单元测试。
 *
 * 三个依赖全 mock（EmbeddingService / QdrantVectorStoreService / DocumentChunkMapper），
 * 不联网、不连库、不连 Qdrant。被测的是"编排"本身：
 *  入参校验 → embed → 存储层 search → MySQL 回查 → 按 score 顺序组装。
 *
 * 与 VectorizationServiceTest 同款套路：mock 返回固定结构，
 * 用 ArgumentCaptor/InOrder 验证"调了谁、传了什么、顺序对不对"。
 * 真实 Qdrant/MySQL/百炼 的端到端验证另见 SearchIntegrationTest。
 */
class SearchServiceTest {

    private EmbeddingService embeddingService;
    private QdrantVectorStoreService qdrantVectorStoreService;
    private DocumentChunkMapper documentChunkMapper;
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        qdrantVectorStoreService = mock(QdrantVectorStoreService.class);
        documentChunkMapper = mock(DocumentChunkMapper.class);
        searchService = new SearchService(embeddingService, qdrantVectorStoreService, documentChunkMapper);
    }

    /** 造一个 1024 维假向量（内容无所谓，单元测试不解码向量）。 */
    private List<Float> fakeVector() {
        Float[] v = new Float[1024];
        java.util.Arrays.fill(v, 0.5f);
        return List.of(v);
    }

    /** 造一个带主键的 chunk 实体——回查原文要 content 有真实值。 */
    private DocumentChunk chunk(long id, int index, String content) {
        DocumentChunk c = new DocumentChunk();
        c.setId(id);
        c.setDocumentId(6L);
        c.setChunkIndex(index);
        c.setContent(content);
        c.setCharCount(content.length());
        return c;
    }

    @Test
    @DisplayName("正常检索：embed→Qdrant search→MySQL 回查→按 score 降序组装 SearchResult")
    void normalSearchAssemblesResultsInScoreOrder() {
        String query = "DocFlow 怎么做检索";
        when(embeddingService.embed(query)).thenReturn(fakeVector());
        // Qdrant 已按 score 降序返回：0.9、0.7、0.5（顺序就是最终顺序）
        when(qdrantVectorStoreService.search(any(), eq(6L), anyInt())).thenReturn(List.of(
                new ScoredVector(3L, 0.9f, 6L, 3L, 2),
                new ScoredVector(1L, 0.7f, 6L, 1L, 0),
                new ScoredVector(2L, 0.5f, 6L, 2L, 1)));
        when(documentChunkMapper.findByIds(any())).thenReturn(List.of(
                chunk(1L, 0, "第一块原文"),
                chunk(2L, 1, "第二块原文"),
                chunk(3L, 2, "第三块原文")));

        List<SearchResult> results = searchService.search(query, 6L, 3);

        assertEquals(3, results.size());
        // 关键：顺序与 Qdrant 的 score 降序一致，不被 MySQL 的返回顺序打乱
        assertEquals(3L, results.get(0).chunkId());
        assertEquals(0.9f, results.get(0).score(), 1e-6);
        assertEquals("第三块原文", results.get(0).content());
        assertEquals(2, results.get(0).chunkIndex());
        // Qdrant 顺序是 [3, 1, 2]（按 score 0.9/0.7/0.5），第三条是 chunkId=2
        assertEquals(2L, results.get(2).chunkId());
        assertEquals(0.5f, results.get(2).score(), 1e-6);

        // 顺序：先 embed，再 Qdrant search，最后 MySQL 回查
        InOrder order = inOrder(embeddingService, qdrantVectorStoreService, documentChunkMapper);
        order.verify(embeddingService).embed(query);
        order.verify(qdrantVectorStoreService).search(any(), eq(6L), eq(3));
        order.verify(documentChunkMapper).findByIds(any());
    }

    @Test
    @DisplayName("空问题：抛 IllegalArgumentException，embed 从未被调用（省一次百炼 API）")
    void blankQueryRejectedBeforeEmbed() {
        assertThrows(IllegalArgumentException.class, () -> searchService.search("   ", null, 5));
        assertThrows(IllegalArgumentException.class, () -> searchService.search(null, null, 5));
        assertThrows(IllegalArgumentException.class, () -> searchService.search("", null, 5));

        verify(embeddingService, never()).embed(any());
        verify(qdrantVectorStoreService, never()).search(any(), any(), anyInt());
        verify(documentChunkMapper, never()).findByIds(any());
    }

    @Test
    @DisplayName("topK < 1：抛 IllegalArgumentException，embed 从未被调用")
    void topKTooSmallRejected() {
        assertThrows(IllegalArgumentException.class, () -> searchService.search("问题", null, 0));
        assertThrows(IllegalArgumentException.class, () -> searchService.search("问题", null, -3));

        verify(embeddingService, never()).embed(any());
    }

    @Test
    @DisplayName("topK > 50：钳到 50，传给存储层的是 50（防御性钳制，不抛错）")
    void topKClampedToMax() {
        when(embeddingService.embed(any())).thenReturn(fakeVector());
        when(qdrantVectorStoreService.search(any(), any(), eq(50))).thenReturn(List.of());

        searchService.search("问题", null, 999);

        // 关键：传给存储层的是 50，不是 999
        verify(qdrantVectorStoreService).search(any(), any(), eq(50));
    }

    @Test
    @DisplayName("documentId=null：传给存储层 null（全库搜），存储层不下 filter")
    void nullDocumentIdMeansGlobalSearch() {
        when(embeddingService.embed(any())).thenReturn(fakeVector());
        when(qdrantVectorStoreService.search(any(), eq(null), anyInt())).thenReturn(List.of());

        searchService.search("问题", null, 5);

        verify(qdrantVectorStoreService).search(any(), eq(null), eq(5));
    }

    @Test
    @DisplayName("Qdrant 返回空：直接返回空 List，不查 MySQL（省一次往返）")
    void emptyQdrantResultSkipsMysql() {
        when(embeddingService.embed(any())).thenReturn(fakeVector());
        when(qdrantVectorStoreService.search(any(), any(), anyInt())).thenReturn(List.of());

        List<SearchResult> results = searchService.search("无人问津的问题", null, 5);

        assertTrue(results.isEmpty());
        verify(documentChunkMapper, never()).findByIds(any());
    }

    @Test
    @DisplayName("孤儿 Point：Qdrant 返回的 chunkId 在 MySQL 找不到→跳过该条，其余正常返回")
    void orphanPointSkipped() {
        when(embeddingService.embed(any())).thenReturn(fakeVector());
        // Qdrant 返回 3 条，但 MySQL 只有 chunkId=1/3，chunkId=2 是孤儿
        when(qdrantVectorStoreService.search(any(), any(), anyInt())).thenReturn(List.of(
                new ScoredVector(3L, 0.9f, 6L, 3L, 2),
                new ScoredVector(2L, 0.8f, 6L, 2L, 1),  // 孤儿
                new ScoredVector(1L, 0.7f, 6L, 1L, 0)));
        when(documentChunkMapper.findByIds(any())).thenReturn(List.of(
                chunk(1L, 0, "第一块"),
                chunk(3L, 2, "第三块")));

        List<SearchResult> results = searchService.search("问题", null, 5);

        assertEquals(2, results.size());  // 孤儿被跳过
        assertEquals(3L, results.get(0).chunkId());
        assertEquals(1L, results.get(1).chunkId());
    }

    @Test
    @DisplayName("Qdrant 服务不可用：存储层抛 QdrantStorageException，原样透传，不查 MySQL")
    void qdrantUnavailablePropagates() {
        when(embeddingService.embed(any())).thenReturn(fakeVector());
        when(qdrantVectorStoreService.search(any(), any(), anyInt()))
                .thenThrow(new QdrantStorageException("向量相似度搜索失败：连接拒绝", new RuntimeException()));

        QdrantStorageException e = assertThrows(QdrantStorageException.class,
                () -> searchService.search("问题", null, 5));

        assertTrue(e.getMessage().contains("连接拒绝"));
        verify(documentChunkMapper, never()).findByIds(any());
    }

    @Test
    @DisplayName("Embedding 失败：异常原样透传，存储层和 MySQL 都不被调用")
    void embeddingFailurePropagates() {
        when(embeddingService.embed(any()))
                .thenThrow(new IllegalArgumentException("百炼限流"));

        assertThrows(IllegalArgumentException.class, () -> searchService.search("问题", null, 5));

        verify(qdrantVectorStoreService, never()).search(any(), any(), anyInt());
        verify(documentChunkMapper, never()).findByIds(any());
    }
}
