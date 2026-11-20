package docflow_backend.service;

import docflow_backend.config.QdrantProperties;
import docflow_backend.exception.QdrantStorageException;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * Day 28：Qdrant 存储 Service——只管"向量进、向量存、向量删"，不管嵌入。
 *
 * 职责边界（三层分工，每层只做一件事）：
 * - EmbeddingService：文本 → 向量（调百炼 API，Day 25）；
 * - VectorizationService：编排（查块 → 逐条嵌入 → 交给本类，Day 28）；
 * - 本类：把向量按约定的 Point 结构写进 Qdrant / 删掉 / 数个数。
 *   它不认识 DocumentChunk、不调 Embedding——所以将来加新文档类型、
 *   换 embedding 模型，本类一行不用改。
 *
 * Point 结构约定（设计文档见 Day 28 讨论记录）：
 * - Point ID = MySQL document_chunks.id（自增主键，全局唯一）→ 一块一行，
 *   重复 upsert 同 ID 覆盖，天然幂等；
 * - Vector = 该块正文算出的 1024 维向量；
 * - Payload 只存三个检索必需字段：documentId / chunkId / chunkIndex。
 *   绝不把 content、charCount、createdAt 搬进 Qdrant——原文的事实源是 MySQL，
 *   双写迟早漂移，检索结果拿 chunkId 回 MySQL 取原文即可。
 *
 * 防重复的两层设计：
 * 1. upsert 幂等（同 Point ID 覆盖，应对"同一批块重复执行"）；
 * 2. 文档级先删后写（应对"重新分块产生新 chunk id、旧 Point 变孤儿"）：
 *    写入前按 payload.documentId 过滤删除该文档全部旧 Point，再整批 upsert。
 */
@Service
public class QdrantVectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStoreService.class);

    private final QdrantClient qdrantClient;
    private final QdrantProperties qdrantProperties;

    public QdrantVectorStoreService(QdrantClient qdrantClient, QdrantProperties qdrantProperties) {
        this.qdrantClient = qdrantClient;
        this.qdrantProperties = qdrantProperties;
    }

    /**
     * 一个 chunk 的"入库包裹"：谁来用、哪一块、第几块、什么向量。
     * 本类对外只认它，不认 DocumentChunk 实体——存储层不该背负业务实体的字段包袱
     * （同 Day 24 的 TextChunk → DocumentChunk 转换思路：边界上做一次明确翻译）。
     *
     * documentId 在 payload 和参数里各有一份是有意的：按 payload 过滤删除时
     * 要用它，单独把它放方法参数里让"删除哪些"的语义写在签名上，一看就懂。
     */
    public record ChunkVector(long documentId, long chunkId, int chunkIndex, List<Float> vector) {
    }

    /**
     * 确保 Collection 存在且规格正确——幂等，重复调用安全。
     *
     * 三种结局：
     * - 不存在 → 创建：size = 配置维度（1024），distance = Cosine；
     * - 存在且规格一致 → 什么都不做（"检查"通过）；
     * - 存在但维度/距离不符 → 抛 IllegalStateException。这一步是给
     *   "配置改了、旧 collection 还在"的情况兜底：宁可启动后第一次调用就炸，
     *   也不默默把 1024 维的向量写进 1536 维的库（那会在 upsert 时才报错，
     *   且错误信息只是底层 gRPC 的字段不匹配，难排查）。
     */
    public void ensureCollection() {
        String collectionName = qdrantProperties.getCollection();

        List<String> existing = unwrap("列出 Collection", () -> qdrantClient.listCollectionsAsync());

        if (existing.contains(collectionName)) {
            verifyExistingCollection(collectionName);
            return;
        }

        VectorParams vectorParams = VectorParams.newBuilder()
                .setSize(qdrantProperties.getVectorDimension())
                .setDistance(Distance.Cosine)
                .build();
        unwrap("创建 Collection " + collectionName,
                () -> qdrantClient.createCollectionAsync(collectionName, vectorParams));
        log.info("Qdrant Collection 已创建：{}（size={}, distance=Cosine）",
                collectionName, qdrantProperties.getVectorDimension());
    }

    /**
     * 写入一个文档的全部向量——"先删后写"，覆盖旧数据。
     *
     * 三步的顺序是有讲究的，不能颠倒：
     * 1. 先校验全部维度（任何一块不匹配立即抛错）：校验放在删除之前，
     *    保证"半批数据"永远不会清掉库里的旧好数据——fail-fast 优于 fail-mess；
     * 2. 再按 documentId 删旧 Point（0 个也合法：文档从没向量化过）；
     * 3. 最后整批 upsert 新 Point（一次网络往返，不是循环发 N 次）。
     *
     * 注意第 2、3 步之间不是原子的（Qdrant 没有 MySQL 那种跨操作事务）：
     * 删完写一半时进程挂掉，文档会缺块。但缺块可通过重跑本方法自愈
     * （重跑会重新删+写全量），且检索拿到的每个 Point 都能对上 MySQL 真块，
     * 不会出现"检索出幽灵内容"。可接受的取舍，如实记录。
     *
     * 空列表合法：只执行删除（清掉该文档在 Qdrant 的所有旧向量），写入跳过——
     * 与 chunkAndSave 的"空文档删旧块不插入"口径一致。
     */
    public int storeDocumentVectors(Long documentId, List<ChunkVector> chunkVectors) {
        String collectionName = qdrantProperties.getCollection();

        // 1. 先全量校验维度（未通过时这里就抛了，删都还没开始，旧数据完好）。
        List<PointStruct> points = new ArrayList<>(chunkVectors.size());
        for (ChunkVector chunkVector : chunkVectors) {
            points.add(toPoint(chunkVector));
        }

        // 2. 按文档删旧 Point：应对"重新分块后 chunk.id 变了"的孤儿问题。
        deleteByDocumentId(documentId);

        // 3. 整批 upsert：Point ID 固定 = chunk.id，同 ID 覆盖不新增。
        if (!points.isEmpty()) {
            unwrap("upsert 文档向量 documentId=" + documentId,
                    () -> qdrantClient.upsertAsync(collectionName, points));
        }
        return points.size();
    }

    /**
     * 写入单个 Chunk 的向量——不加文档级删除，纯 upsert。
     *
     * 使用场景：单块修补 / 测试验证。幂等：同 chunkId 重复调用覆盖同一 Point。
     * 正常业务流程走 storeDocumentVectors（整文档先删后写），不走这里。
     */
    public void upsertChunk(ChunkVector chunkVector) {
        String collectionName = qdrantProperties.getCollection();
        PointStruct point = toPoint(chunkVector);
        unwrap("upsert 单块向量 chunkId=" + chunkVector.chunkId(),
                () -> qdrantClient.upsertAsync(collectionName, List.of(point)));
    }

    /**
     * 删掉一篇文档在 Qdrant 的全部 Point（按 payload.documentId 过滤）。
     * 返回的删除语义与 DocumentChunkMapper.deleteByDocumentId 一致：
     * 0 个也是合法结果（该文档从没向量化过）。
     */
    public void deleteByDocumentId(Long documentId) {
        String collectionName = qdrantProperties.getCollection();
        Filter filter = Filter.newBuilder()
                .addMust(ConditionFactory.match("documentId", documentId))
                .build();
        unwrap("按 documentId=" + documentId + " 删除旧向量",
                () -> qdrantClient.deleteAsync(collectionName, filter));
    }

    /**
     * 数一篇文档在 Qdrant 里的 Point 数——给测试和运维"眼见为实"用：
     * 写完了到底几条？重复执行后有没有翻倍？这个数不会说谎。
     */
    public long countByDocumentId(Long documentId) {
        String collectionName = qdrantProperties.getCollection();
        Filter filter = Filter.newBuilder()
                .addMust(ConditionFactory.match("documentId", documentId))
                .build();
        return unwrap("统计 documentId=" + documentId + " 的向量数",
                () -> qdrantClient.countAsync(collectionName, filter, true));
    }

    /**
     * ChunkVector → PointStruct 的翻译 + 维度断言。
     *
     * 维度为什么在这里拦而不是等着 Qdrant 拒收：
     * - Qdrant 的报错是 gRPC 底层的字段不匹配，难读；
     * - 更重要的是：config 里的 1024 是我们自己配的，模型真实输出从未实测
     *   （Day 26/27 都是 mock），这道断言就是"真实维度体检"的第一道岗——
     *   模型哪天悄悄变了维度，第一次真实写入就会被这里用中文明说拦下。
     */
    private PointStruct toPoint(ChunkVector chunkVector) {
        List<Float> vector = chunkVector.vector();
        int expected = qdrantProperties.getVectorDimension();
        if (vector == null || vector.size() != expected) {
            throw new IllegalStateException("向量维度不匹配：期望 " + expected
                    + " 维，实际 " + (vector == null ? "null" : vector.size())
                    + " 维。请检查 spring.ai.openai.embedding.dimensions 与 embedding 模型是否匹配");
        }

        return PointStruct.newBuilder()
                .setId(id(chunkVector.chunkId()))
                .setVectors(vectors(vector))
                .putPayload("documentId", value(chunkVector.documentId()))
                .putPayload("chunkId", value(chunkVector.chunkId()))
                .putPayload("chunkIndex", value(chunkVector.chunkIndex()))
                .build();
    }

    /** 已存在的 Collection 规格（维度 × 距离）与配置比对，不符即抛错。 */
    private void verifyExistingCollection(String collectionName) {
        CollectionInfo info = unwrap("查询 Collection " + collectionName + " 的信息",
                () -> qdrantClient.getCollectionInfoAsync(collectionName));

        VectorsConfig vectorsConfig = info.getConfig().getParams().getVectorsConfig();
        // 本项目约定"单一无名向量"的 collection。hasParams() 为 false 说明
        // collection 用的是"命名向量"（多套向量并存）的建法——不是本项目的
        // 建法，直接明说，别硬去读默认值得出"0 维"的误导性结论。
        if (!vectorsConfig.hasParams()) {
            throw new IllegalStateException("Qdrant Collection " + collectionName
                    + " 使用命名向量配置，与本项目单向量约定不符，请删除重建");
        }
        VectorParams params = vectorsConfig.getParams();
        if (params.getSize() != qdrantProperties.getVectorDimension()) {
            throw new IllegalStateException("Qdrant Collection " + collectionName
                    + " 已存在但维度是 " + params.getSize() + "，配置要求 "
                    + qdrantProperties.getVectorDimension()
                    + "。请核对 spring.ai.openai.embedding.dimensions，或删除旧 Collection 重建");
        }
        if (params.getDistance() != Distance.Cosine) {
            throw new IllegalStateException("Qdrant Collection " + collectionName
                    + " 的距离度量是 " + params.getDistance() + "，与设计约定 Cosine 不符");
        }
    }

    /**
     * ListenableFuture 的统一拆包口——本类所有网络调用都从这里过，
     * 三类失败在这翻译成"人话"：
     * - InterruptedException：本线程等结果时被打断（关停等场景）→ 恢复中断位
     *   再抛，别吞掉线程池的关停信号；
     * - ExecutionException：调用本身失败（连不上、鉴权失败、collection 不存在）
     *   → 解出真凶，与操作描述一起包进 QdrantStorageException；
     * - 其他 RuntimeException：客户端本地就抛了（参数错误等）→ 原样放行，
     *   它们的 message 已经足够明确。
     */
    private <T> T unwrap(String operation, FutureCall<T> call) {
        try {
            return call.invoke().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QdrantStorageException(operation + " 被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new QdrantStorageException(operation + " 失败：" + cause.getMessage(), cause);
        }
    }

    /** 函数式小接口：让 unwrap 接收"任何返回 ListenableFuture 的调用"。 */
    @FunctionalInterface
    private interface FutureCall<T> {
        com.google.common.util.concurrent.ListenableFuture<T> invoke();
    }
}
