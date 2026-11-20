package docflow_backend.service;

import docflow_backend.config.QdrantProperties;
import docflow_backend.service.QdrantVectorStoreService.ChunkVector;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.VectorsFactory.vectors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day 28：真实 Qdrant 集成测试——不 mock，直接 gRPC 连本地 Qdrant。
 *
 * 与单元测试（QdrantVectorStoreServiceTest，mock QdrantClient）的分界：
 * - 单元测试：验证"组装与把关"逻辑，不碰真实服务器；
 * - 本测试：验证"真的能连、真的能存、真的能查"，是第五阶段验收的主证据。
 *
 * 运行前提：本地 Qdrant 已启动，gRPC 监听 6334（Windows 原生 exe：
 *   cd C:\Users\ye\qdrant-local && qdrant.exe）。
 *
 * 用 @Tag("integration") 标记，默认 mvn test 会因"没定义这个 tag 的包含规则"
 * 而一并执行——为避免它污染普通单元测试（依赖外部 Qdrant），单独运行时：
 *   mvn -Dtest=QdrantIntegrationTest -DfailIfNoTests=false test
 * 或按 tag 过滤（见本文件尾注释）。这里直接构造 QdrantClient + Service，
 * 不起 Spring 容器，绕开 HTTP 代理对 127.0.0.1 的干扰（gRPC 走 TCP 不受影响）。
 *
 * 顺序依赖（@Order + @TestMethodOrder）：先建 collection，再写单条/批量，
 * 最后统计与删除——后面的断言依赖前面的写入，串行保序最直观。
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QdrantIntegrationTest {

    private static final String COLLECTION = "doc_chunks_it";
    private static final int DIM = 1024;

    private static QdrantClient qdrantClient;
    private static QdrantVectorStoreService storeService;

    @BeforeAll
    static void setUp() {
        // 直接构造客户端 + 配置 + 服务，不依赖 Spring 容器。
        QdrantProperties props = new QdrantProperties();
        props.setCollection(COLLECTION);
        props.setVectorDimension(DIM);

        qdrantClient = new QdrantClient(
                QdrantGrpcClient.newBuilder("localhost", 6334, false).build());
        storeService = new QdrantVectorStoreService(qdrantClient, props);
    }

    @AfterAll
    static void tearDown() {
        if (qdrantClient != null) {
            qdrantClient.close();
        }
    }

    /** 造一个 1024 维向量：前 3 维可辨识，便于 assert 内容。 */
    private static List<Float> vector(float seed) {
        List<Float> v = new ArrayList<>(DIM);
        v.add(seed);
        v.add(seed + 0.1f);
        v.add(seed + 0.2f);
        for (int i = 3; i < DIM; i++) {
            v.add(0.0f);
        }
        return v;
    }

    @Test
    @Order(1)
    @DisplayName("1) Qdrant 连接正常 + Collection 创建成功（1024 维 Cosine）")
    void connectionAndCollectionCreation() throws Exception {
        // 先确保一个干净的起点：若上次残留同名 collection，先删。
        if (qdrantClient.collectionExistsAsync(COLLECTION).get()) {
            qdrantClient.deleteCollectionAsync(COLLECTION).get();
        }

        storeService.ensureCollection();

        assertTrue(qdrantClient.collectionExistsAsync(COLLECTION).get(), "collection 应存在");

        CollectionInfo info = qdrantClient.getCollectionInfoAsync(COLLECTION).get();
        VectorParams params = info.getConfig().getParams().getVectorsConfig().getParams();
        assertEquals(DIM, params.getSize(), "collection 维度应为 1024");
        assertEquals(Distance.Cosine, params.getDistance(), "距离度量应为 Cosine");
    }

    @Test
    @Order(2)
    @DisplayName("2) 单条向量写入 + Point ID/向量内容/payload 三项都正确")
    void singleUpsertWritesCorrectPoint() throws Exception {
        long chunkId = 50001L;
        storeService.upsertChunk(new ChunkVector(1001L, chunkId, 0, vector(1.0f)));

        List<RetrievedPoint> points = qdrantClient.scrollAsync(
                io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                        .setCollectionName(COLLECTION)
                        .setLimit(100)
                        .setWithPayload(
                                io.qdrant.client.WithPayloadSelectorFactory.enable(true))
                        .setWithVectors(
                                io.qdrant.client.WithVectorsSelectorFactory.enable(true))
                        .build())
                .get()
                .getResultList();

        RetrievedPoint target = points.stream()
                .filter(p -> p.getId().getNum() == chunkId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到 chunkId=" + chunkId + " 的 Point"));

        // Point ID 与 chunkId 一致
        assertEquals(chunkId, target.getId().getNum());
        // payload 三个字段
        assertEquals(1001L, target.getPayloadMap().get("documentId").getIntegerValue());
        assertEquals(chunkId, target.getPayloadMap().get("chunkId").getIntegerValue());
        assertEquals(0, target.getPayloadMap().get("chunkIndex").getIntegerValue());
        // 向量 1024 维
        assertEquals(DIM, target.getVectors().getVector().getDense().getDataCount());
        // 重要发现：Cosine 距离的 collection 会对向量做 L2 归一化再存储，
        // 所以原始 seed 值不再是 1.0。这里校验"确实有非零向量写入"以及
        // "归一化后模长≈1"（各分量平方和≈1），不校验绝对数值。
        float firstDim = target.getVectors().getVector().getDense().getData(0);
        assertTrue(Math.abs(firstDim) > 0.0f, "第一维应非零（确实写入了向量）");
        double squaredSum = 0.0;
        for (int i = 0; i < DIM; i++) {
            float v = target.getVectors().getVector().getDense().getData(i);
            squaredSum += (double) v * v;
        }
        assertEquals(1.0, squaredSum, 1e-3, "归一化后向量模长应约为 1");
    }

    @Test
    @Order(3)
    @DisplayName("3) 批量写入一个文档的多个向量 + documentId 关联正确")
    void batchUpsertWritesAllPoints() throws Exception {
        long documentId = 1002L;
        List<ChunkVector> chunkVectors = List.of(
                new ChunkVector(documentId, 60001L, 0, vector(2.0f)),
                new ChunkVector(documentId, 60002L, 1, vector(3.0f)),
                new ChunkVector(documentId, 60003L, 2, vector(4.0f)));

        int written = storeService.storeDocumentVectors(documentId, chunkVectors);

        assertEquals(3, written, "应写入 3 个 Point");
        assertEquals(3L, storeService.countByDocumentId(documentId), "documentId=1002 应有 3 条");
    }

    @Test
    @Order(4)
    @DisplayName("4) 重复执行不产生重复 Point（幂等，先删后写覆盖）")
    void repeatedExecutionDoesNotDuplicate() throws Exception {
        long documentId = 1002L;
        List<ChunkVector> sameAgain = List.of(
                new ChunkVector(documentId, 60001L, 0, vector(2.0f)),
                new ChunkVector(documentId, 60002L, 1, vector(3.0f)),
                new ChunkVector(documentId, 60003L, 2, vector(4.0f)));

        // 再执行一次完全相同的写入
        storeService.storeDocumentVectors(documentId, sameAgain);

        // 关键：数量仍是 3，不是 6
        assertEquals(3L, storeService.countByDocumentId(documentId),
                "重复执行后 documentId=1002 应仍是 3 条，不是 6 条");
    }

    @Test
    @Order(5)
    @DisplayName("5) 重新分块场景：新 chunkId 写入后，旧 chunkId 的孤儿 Point 被清理")
    void repartitionRemovesOrphanPoints() throws Exception {
        long documentId = 1002L;
        // 模拟"重新分块"：chunkId 变了（新自增主键），块数也变了（3 → 2）
        List<ChunkVector> newChunks = List.of(
                new ChunkVector(documentId, 70001L, 0, vector(5.0f)),
                new ChunkVector(documentId, 70002L, 1, vector(6.0f)));

        storeService.storeDocumentVectors(documentId, newChunks);

        assertEquals(2L, storeService.countByDocumentId(documentId),
                "重新分块后应只剩 2 条");

        // 旧的 chunkId 60001 不应再存在
        List<RetrievedPoint> points = scrollAll();
        boolean oldPointStillExists = points.stream()
                .anyMatch(p -> p.getId().getNum() == 60001L);
        assertTrue(!oldPointStillExists, "旧 chunkId=60001 的孤儿 Point 应已被清理");
    }

    @Test
    @Order(6)
    @DisplayName("6) 向量维度不匹配：写入前即被断言拦截（fail-fast）")
    void dimensionMismatchIsRejected() {
        List<Float> wrongDim = List.of(0.1f, 0.2f, 0.3f); // 3 维
        assertThrows(IllegalStateException.class,
                () -> storeService.upsertChunk(
                        new ChunkVector(1003L, 80001L, 0, wrongDim)));
    }

    @Test
    @Order(7)
    @DisplayName("7) 按 documentId 删除：全部 Point 清空")
    void deleteByDocumentIdClearsAll() throws Exception {
        long documentId = 1002L;
        storeService.deleteByDocumentId(documentId);

        assertEquals(0L, storeService.countByDocumentId(documentId),
                "删除后 documentId=1002 应为 0 条");
    }

    private List<RetrievedPoint> scrollAll() throws Exception {
        ScrollResponse response = qdrantClient.scrollAsync(
                io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                        .setCollectionName(COLLECTION)
                        .setLimit(1000)
                        .build())
                .get(10, TimeUnit.SECONDS);
        return response.getResultList();
    }
}
