package docflow_backend.service;

import docflow_backend.dto.SearchResult;
import docflow_backend.mapper.DocumentChunkMapper;
import docflow_backend.model.DocumentChunk;
import docflow_backend.service.QdrantVectorStoreService.ScoredVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Day 29：语义检索编排 Service——"问题进，最相关的 Chunk 出"这件事的总指挥。
 *
 * 与 VectorizationService（Day 28）是镜像关系：
 * - VectorizationService：原文 → embed → 写进 Qdrant（"入库"方向）；
 * - SearchService        ：问题 → embed → 查 Qdrant → 回 MySQL 取原文（"检索"方向）。
 * 两者都复用 EmbeddingService（同一份"文本→向量"能力，不重复实现），都把 Qdrant
 * I/O 托给 QdrantVectorStoreService，自己只做编排——分层一致，职责对称。
 *
 * 为什么独立成类而不塞进 DocumentService（同 Day 28 的取舍）：
 * 1. DocumentService 的构造器已有 8 个依赖，再塞检索三件套（Embedding +
 *    QdrantStore + ChunkMapper）故事重演，独立的类自带依赖、现有类零修改；
 * 2. 检索不是"文档 CRUD"，是一条独立的"问答前置"业务线，与 DocumentService 平级更诚实；
 * 3. 不修改 Day 27 的 batchEmbed（只算不存）——它和检索各走各的，互不影响。
 *
 * 编排四步（对应需求第 1~7 条）：
 * 1. embeddingService.embed(query)：问题也转成向量（复用，不重写 Embedding API）；
 * 2. qdrantVectorStoreService.search(queryVector, documentId, topK)：Qdrant 相似度搜索，
 *    返回 ScoredVector（含 pointId/score/documentId/chunkId/chunkIndex），按 score 降序；
 * 3. documentChunkMapper.findByIds(chunkIds)：一次 MySQL 往返取回全部命中块原文；
 * 4. 组装 SearchResult：Qdrant 的 score + MySQL 的 content，保持 Qdrant 的顺序。
 *
 * 不接大语言模型：本类到"把最相关的几段原文找出来"为止，不生成回答——
 * 生成回答是后面 RAG 流程的事，今天只把"检索"这步做扎实。这是有意的边界：
 * 检索质量可独立验证（看 score 和命中是否合理），不被生成质量拖累或掩盖。
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /**
     * topK 的合理上限。设 50：再大没意义——RAG 喂给大模型的上下文窗口有限，
     * 超过几十段原文既费 token 又稀释信号。下限由校验拦在 1。
     * 这是"防御性钳制"：调用方传 999 不会让 Qdrant 真去翻 999 条，而是钳到 50。
     */
    static final int MAX_TOP_K = 50;

    private final EmbeddingService embeddingService;
    private final QdrantVectorStoreService qdrantVectorStoreService;
    private final DocumentChunkMapper documentChunkMapper;

    public SearchService(EmbeddingService embeddingService,
                         QdrantVectorStoreService qdrantVectorStoreService,
                         DocumentChunkMapper documentChunkMapper) {
        this.embeddingService = embeddingService;
        this.qdrantVectorStoreService = qdrantVectorStoreService;
        this.documentChunkMapper = documentChunkMapper;
    }

    /**
     * 语义检索：拿一段用户问题，返回 Qdrant 里最相关的 topK 个 Chunk（含原文与分数）。
     *
     * @param query      用户问题，不能为空——空问题没有语义，转不出有意义的向量，
     *                   不该浪费一次百炼 API 调用（与 EmbeddingService.embed 同口径）。
     * @param documentId 可选过滤；null = 全库搜，非 null = 只在该文档内搜。
     *                   不存在的 documentId 不会报错，Qdrant 的 filter 命中 0 个 → 返回空 List。
     * @param topK       返回条数上限；&lt; 1 抛错，&gt; 50 钳到 50。
     * @return 按 score 降序的结果列表，可能为空（库里没数据 / filter 没命中）。
     *         每条含 pointId/chunkId/documentId/chunkIndex/content/score。
     */
    public List<SearchResult> search(String query, Long documentId, int topK) {
        // 1. 入参校验：空问题直接拦——embed 本身也会拦，但早拦省一次方法调用，且错误信息更贴场景。
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空：空问题没有语义，无法转成查询向量");
        }
        // topK 边界：< 1 是调用方写错（抛错）；> 50 钳到 50（防御，不抛错）。
        if (topK < 1) {
            throw new IllegalArgumentException("topK 必须 >= 1，实际：" + topK);
        }
        int effectiveTopK = Math.min(topK, MAX_TOP_K);

        // 2. 问题 → 向量。复用 EmbeddingService，不重复实现 Embedding API 调用。
        //    query 和文档正文走同一个 embed：同一个模型、同一份维度配置，
        //    保证"问题向量"与"文档向量"在同一坐标系里，相似度才有意义。
        List<Float> queryVector = embeddingService.embed(query);

        // 3. Qdrant 相似度搜索：返回 ScoredVector，已按 score 降序。
        //    documentId=null 时不限文档（全库搜）；非 null 时存储层下 payload filter。
        List<ScoredVector> scoredVectors = qdrantVectorStoreService.search(queryVector, documentId, effectiveTopK);

        // 4. 空结果快速返回：不查 MySQL，省一次往返。合法结果，不是异常。
        if (scoredVectors.isEmpty()) {
            return List.of();
        }

        // 5. 一次性回查 MySQL：把命中的 chunkId 收齐，一条 IN 查询拿回全部原文。
        //    不循环单查：topK 条就 topK 次网络往返，不可接受。
        List<Long> chunkIds = new ArrayList<>(scoredVectors.size());
        for (ScoredVector sv : scoredVectors) {
            chunkIds.add(sv.chunkId());
        }
        List<DocumentChunk> chunks = documentChunkMapper.findByIds(chunkIds);

        // 6. id → DocumentChunk 的查找表。MySQL 返回顺序不定（通常按主键），
        //    而 SearchService 要保持 Qdrant 的 score 降序，所以先建表再按 score 顺序取。
        Map<Long, DocumentChunk> chunkById = new HashMap<>(chunks.size() * 2);
        for (DocumentChunk chunk : chunks) {
            chunkById.put(chunk.getId(), chunk);
        }

        // 7. 组装 SearchResult：按 Qdrant 的 score 顺序（scoredVectors 已降序）逐条拼。
        //    理论上每个 chunkId 都该在 MySQL 里（写入时 Point ID 就是 chunk 主键），
        //    找不到说明数据漂移（Qdrant 有点、MySQL 行被删）——记 warn 跳过，不抛错：
        //    少一条不该让整次检索失败，但必须留痕，运维据此查"孤儿 Point"。
        List<SearchResult> results = new ArrayList<>(scoredVectors.size());
        for (ScoredVector sv : scoredVectors) {
            DocumentChunk chunk = chunkById.get(sv.chunkId());
            if (chunk == null) {
                log.warn("孤儿 Point：Qdrant 返回 chunkId={} 但 MySQL document_chunks 里找不到，已跳过",
                        sv.chunkId());
                continue;
            }
            results.add(new SearchResult(
                    sv.pointId(),
                    sv.chunkId(),
                    sv.documentId(),
                    sv.chunkIndex(),
                    chunk.getContent(),
                    sv.score()));
        }
        return results;
    }
}
