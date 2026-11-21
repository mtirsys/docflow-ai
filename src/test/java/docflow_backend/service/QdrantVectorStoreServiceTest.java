package docflow_backend.service;

import com.google.common.util.concurrent.Futures;
import docflow_backend.config.QdrantProperties;
import docflow_backend.exception.QdrantStorageException;
import docflow_backend.service.QdrantVectorStoreService.ChunkVector;
import docflow_backend.service.QdrantVectorStoreService.ScoredVector;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionConfig;
import io.qdrant.client.grpc.Collections.CollectionParams;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.UpdateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Day 28：QdrantVectorStoreService（Qdrant 存储层）的单元测试。
 *
 * 关键约定（与 EmbeddingBatchServiceTest 一致）：QdrantClient 用 Mockito mock，
 * 不需要真实 Qdrant 在跑。被测的是存储层的"组装与把关"逻辑——
 * Collection 幂等创建与规格校验、Point 组装（ID/payload/维度）、
 * 先删后写的顺序、异常翻译——而不是"Qdrant 服务器是否真的能存"
 * （那属于真实集成测试，需要本地起 Qdrant，另见集成验证记录）。
 *
 * mock 返回值都是立即完成的 ListenableFuture（Futures.immediateFuture），
 * 模拟"网络调用成功返回"；失败路径用 immediateFailedFuture 模拟网络故障。
 */
class QdrantVectorStoreServiceTest {

    private QdrantClient qdrantClient;
    private QdrantProperties properties;
    private QdrantVectorStoreService storeService;

    @BeforeEach
    void setUp() {
        qdrantClient = mock(QdrantClient.class);
        // 直接 new 配置对象：与生产相同的默认值（doc_chunks / 1024 维），
        // 不起 Spring 容器——配置类是普通 POJO 正是为此。
        properties = new QdrantProperties();
        storeService = new QdrantVectorStoreService(qdrantClient, properties);
    }

    /** 造一个 1024 维的假向量。 */
    private List<Float> fakeVector() {
        Float[] v = new Float[1024];
        java.util.Arrays.fill(v, 0.25f);
        return List.of(v);
    }

    private ChunkVector chunkVector(long documentId, long chunkId, int chunkIndex) {
        return new ChunkVector(documentId, chunkId, chunkIndex, fakeVector());
    }

    /** 造一个"已存在且规格正确"的 CollectionInfo（1024 维 + Cosine）。 */
    private CollectionInfo correctCollectionInfo() {
        return CollectionInfo.newBuilder()
                .setConfig(CollectionConfig.newBuilder()
                        .setParams(CollectionParams.newBuilder()
                                .setVectorsConfig(VectorsConfig.newBuilder()
                                        .setParams(VectorParams.newBuilder()
                                                .setSize(1024)
                                                .setDistance(Distance.Cosine)))))
                .build();
    }

    @Test
    @DisplayName("Collection 不存在时创建它：1024 维 + Cosine，参数逐项正确")
    void ensureCollectionCreatesWhenAbsent() {
        when(qdrantClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(qdrantClient.createCollectionAsync(anyString(), any(VectorParams.class)))
                .thenReturn(Futures.immediateFuture(
                        io.qdrant.client.grpc.Collections.CollectionOperationResponse.getDefaultInstance()));

        storeService.ensureCollection();

        ArgumentCaptor<VectorParams> captor = ArgumentCaptor.forClass(VectorParams.class);
        verify(qdrantClient).createCollectionAsync(anyString(), captor.capture());
        assertEquals(1024, captor.getValue().getSize());
        assertEquals(Distance.Cosine, captor.getValue().getDistance());
    }

    @Test
    @DisplayName("Collection 已存在且规格一致：什么都不做（幂等，不重复创建）")
    void ensureCollectionNoopWhenPresentAndCorrect() {
        when(qdrantClient.listCollectionsAsync())
                .thenReturn(Futures.immediateFuture(List.of("doc_chunks")));
        when(qdrantClient.getCollectionInfoAsync("doc_chunks"))
                .thenReturn(Futures.immediateFuture(correctCollectionInfo()));

        storeService.ensureCollection();

        verify(qdrantClient, never()).createCollectionAsync(anyString(), any(VectorParams.class));
    }

    @Test
    @DisplayName("Collection 已存在但维度不符：抛错明说，拒绝往错规格的库里写")
    void ensureCollectionRejectsWrongDimension() {
        CollectionInfo wrongDims = CollectionInfo.newBuilder()
                .setConfig(CollectionConfig.newBuilder()
                        .setParams(CollectionParams.newBuilder()
                                .setVectorsConfig(VectorsConfig.newBuilder()
                                        .setParams(VectorParams.newBuilder()
                                                .setSize(1536)
                                                .setDistance(Distance.Cosine)))))
                .build();
        when(qdrantClient.listCollectionsAsync())
                .thenReturn(Futures.immediateFuture(List.of("doc_chunks")));
        when(qdrantClient.getCollectionInfoAsync("doc_chunks"))
                .thenReturn(Futures.immediateFuture(wrongDims));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> storeService.ensureCollection());

        assertTrue(e.getMessage().contains("1536"));
        assertTrue(e.getMessage().contains("1024"));
    }

    @Test
    @DisplayName("批量写入：Point ID = chunkId，payload 三字段正确，先删后写的顺序")
    void storeDocumentVectorsBuildsCorrectPoints() throws Exception {
        when(qdrantClient.deleteAsync(anyString(), any(Filter.class)))
                .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));

        int written = storeService.storeDocumentVectors(7L, List.of(
                chunkVector(7L, 101L, 0),
                chunkVector(7L, 102L, 1)));

        assertEquals(2, written);

        // upsert 的 Point 逐项体检：ID 是 chunk 主键，payload 是三个元数据字段。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PointStruct>> captor =
                ArgumentCaptor.forClass((Class<List<PointStruct>>) (Class<?>) List.class);
        verify(qdrantClient).upsertAsync(anyString(), captor.capture());
        List<PointStruct> points = captor.getValue();
        assertEquals(2, points.size());

        PointStruct first = points.get(0);
        assertEquals(101L, first.getId().getNum());
        assertEquals(7L, first.getPayloadMap().get("documentId").getIntegerValue());
        assertEquals(101L, first.getPayloadMap().get("chunkId").getIntegerValue());
        assertEquals(0, first.getPayloadMap().get("chunkIndex").getIntegerValue());
        // 向量按原样传递：1024 个浮点数（1.20 的向量装在 Vector.dense → DenseVector.data 里）
        assertEquals(1024, first.getVectors().getVector().getDense().getDataCount());

        PointStruct second = points.get(1);
        assertEquals(102L, second.getId().getNum());
        assertEquals(1, second.getPayloadMap().get("chunkIndex").getIntegerValue());

        // 先删后写两个动作都发生了
        verify(qdrantClient).deleteAsync(anyString(), any(Filter.class));
        verify(qdrantClient, times(1)).upsertAsync(anyString(), anyList());
    }

    @Test
    @DisplayName("维度不匹配：校验在删除之前就拦截——旧数据完好，Qdrant 零调用")
    void dimensionMismatchFailsFastBeforeAnyWrite() {
        List<Float> wrongDim = List.of(0.1f, 0.2f, 0.3f); // 3 维，不是 1024 维

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> storeService.storeDocumentVectors(7L,
                        List.of(new ChunkVector(7L, 101L, 0, wrongDim))));

        assertTrue(e.getMessage().contains("1024"));
        assertTrue(e.getMessage().contains("3"));
        // 关键：校验失败时连删除都没发生——fail-fast 优于"删完发现写不进"
        verify(qdrantClient, never()).deleteAsync(anyString(), any(Filter.class));
        verify(qdrantClient, never()).upsertAsync(anyString(), anyList());
    }

    @Test
    @DisplayName("空块列表：只清旧向量、不 upsert（与 chunkAndSave 的空文档口径一致）")
    void emptyListOnlyDeletes() throws Exception {
        when(qdrantClient.deleteAsync(anyString(), any(Filter.class)))
                .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));

        int written = storeService.storeDocumentVectors(7L, List.of());

        assertEquals(0, written);
        verify(qdrantClient).deleteAsync(anyString(), any(Filter.class));
        verify(qdrantClient, never()).upsertAsync(anyString(), anyList());
    }

    @Test
    @DisplayName("单块 upsert：一个 Point 写入，不做文档级删除")
    void upsertChunkWritesSinglePoint() {
        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));

        storeService.upsertChunk(chunkVector(7L, 105L, 4));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PointStruct>> captor =
                ArgumentCaptor.forClass((Class<List<PointStruct>>) (Class<?>) List.class);
        verify(qdrantClient).upsertAsync(anyString(), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(105L, captor.getValue().get(0).getId().getNum());
        verify(qdrantClient, never()).deleteAsync(anyString(), any(Filter.class));
    }

    @Test
    @DisplayName("Qdrant 网络故障：底层异常被翻译成 QdrantStorageException，带操作描述")
    void networkFailureIsTranslated() throws Exception {
        when(qdrantClient.deleteAsync(anyString(), any(Filter.class)))
                .thenReturn(Futures.immediateFailedFuture(
                        new ExecutionException(new RuntimeException("连接拒绝"))));

        QdrantStorageException e = assertThrows(QdrantStorageException.class,
                () -> storeService.deleteByDocumentId(7L));

        assertTrue(e.getMessage().contains("删除"));
        assertTrue(e.getMessage().contains("连接拒绝"));
    }

    // ==================== Day 29：search 方法 ====================

    /** 造一个 ScoredPoint：id=chunkId，带三字段 payload，score 指定。 */
    private ScoredPoint scoredPoint(long chunkId, float score, long documentId, int chunkIndex) {
        return ScoredPoint.newBuilder()
                .setId(id(chunkId))
                .setScore(score)
                .putPayload("documentId", value(documentId))
                .putPayload("chunkId", value(chunkId))
                .putPayload("chunkIndex", value(chunkIndex))
                .build();
    }

    @Test
    @DisplayName("search 带 documentId：SearchPoints 的 vector/filter/limit/withPayload 逐项正确")
    void searchBuildsCorrectSearchPointsWithFilter() throws Exception {
        when(qdrantClient.searchAsync(any(SearchPoints.class)))
                .thenReturn(Futures.immediateFuture(List.of()));

        storeService.search(fakeVector(), 6L, 5);

        ArgumentCaptor<SearchPoints> captor = ArgumentCaptor.forClass(SearchPoints.class);
        verify(qdrantClient).searchAsync(captor.capture());
        SearchPoints sp = captor.getValue();
        assertEquals("doc_chunks", sp.getCollectionName());
        assertEquals(1024, sp.getVectorCount(), "查询向量应是 1024 维");
        assertEquals(5L, sp.getLimit(), "limit 应是 topK=5");
        assertTrue(sp.hasFilter(), "documentId 非空时应下发 filter");
        // withPayload 必须开启，否则拿不到 chunkId
        assertTrue(sp.getWithPayload().getEnable(), "withPayload 必须开启");
        // 不带 withVectors（结果不需要向量本身）
        assertFalse(sp.getWithVectors().getEnable(), "withVectors 不应开启（省序列化）");
    }

    @Test
    @DisplayName("search documentId=null：不下发 filter（全库搜）")
    void searchWithoutFilterWhenDocumentIdNull() throws Exception {
        when(qdrantClient.searchAsync(any(SearchPoints.class)))
                .thenReturn(Futures.immediateFuture(List.of()));

        storeService.search(fakeVector(), null, 5);

        ArgumentCaptor<SearchPoints> captor = ArgumentCaptor.forClass(SearchPoints.class);
        verify(qdrantClient).searchAsync(captor.capture());
        assertFalse(captor.getValue().hasFilter(), "documentId=null 时不应有 filter");
    }

    @Test
    @DisplayName("search 返回 ScoredVector：pointId/score/payload 三字段正确翻译")
    void searchTranslatesScoredPointToScoredVector() throws Exception {
        when(qdrantClient.searchAsync(any(SearchPoints.class)))
                .thenReturn(Futures.immediateFuture(List.of(
                        scoredPoint(3L, 0.9f, 6L, 2),
                        scoredPoint(1L, 0.7f, 6L, 0))));

        List<ScoredVector> results = storeService.search(fakeVector(), 6L, 5);

        assertEquals(2, results.size());
        assertEquals(3L, results.get(0).pointId());
        assertEquals(0.9f, results.get(0).score(), 1e-6);
        assertEquals(6L, results.get(0).documentId());
        assertEquals(3L, results.get(0).chunkId());
        assertEquals(2, results.get(0).chunkIndex());
        assertEquals(1L, results.get(1).chunkId());
    }

    @Test
    @DisplayName("search 空结果：返回空 List（合法，不是异常）")
    void searchEmptyResultReturnsEmptyList() throws Exception {
        when(qdrantClient.searchAsync(any(SearchPoints.class)))
                .thenReturn(Futures.immediateFuture(List.of()));

        List<ScoredVector> results = storeService.search(fakeVector(), null, 5);

        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("search 网络故障：底层异常翻译成 QdrantStorageException，带操作描述")
    void searchNetworkFailureTranslated() throws Exception {
        when(qdrantClient.searchAsync(any(SearchPoints.class)))
                .thenReturn(Futures.immediateFailedFuture(
                        new ExecutionException(new RuntimeException("连接拒绝"))));

        QdrantStorageException e = assertThrows(QdrantStorageException.class,
                () -> storeService.search(fakeVector(), null, 5));

        assertTrue(e.getMessage().contains("向量相似度搜索"));
        assertTrue(e.getMessage().contains("连接拒绝"));
    }
}
