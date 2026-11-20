package docflow_backend.service;

import docflow_backend.dto.EmbeddingBatchResult;
import docflow_backend.dto.VectorizationResult;
import docflow_backend.exception.DocumentNotFoundException;
import docflow_backend.mapper.DocumentChunkMapper;
import docflow_backend.model.DocumentChunk;
import docflow_backend.service.QdrantVectorStoreService.ChunkVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Day 28：向量化编排 Service——"算向量并落 Qdrant"这件事的总指挥。
 *
 * 为什么独立成类而不加进 DocumentService（用户确认的设计决策）：
 * 1. DocumentService 的构造器已有 8 个依赖——Day 27 加第 8 参时连锁改了
 *    8 个测试文件。这次再往里塞两个依赖，故事重演。独立的类自己带依赖，
 *    现有类和它的测试一个字不用动（"不修改无关的文档 CRUD 代码"的落地）；
 * 2. 职责上它确实不是"文档 CRUD"：调外部嵌入 API + 写外部向量库，
 *    是一条独立的"向量化"业务线，和 DocumentService 平级更诚实；
 * 3. Day 27 的 batchEmbed（只算不存）保留原样——它是理解"算"与"存"
 *    分两步走的活教材，两者互不影响。
 *
 * 编排四步：
 * 1. documentService.findById(id)：文档不存在 → DocumentNotFoundException（404），
 *    复用既有通道，不自己重复造"查不存在"；
 * 2. documentChunkMapper.findByDocumentId(id)：复用 Day 24 的查询，chunk_index 升序；
 * 3. ensureCollection() + 逐块 embed：任何一块失败则本次完全不落库（见下）；
 * 4. qdrantVectorStoreService.storeDocumentVectors()：整批先删后写，
 *    返回实际写入的 Point 数。
 *
 * 关于失败策略——这里与 Day 27 的 batchEmbed 刻意相反：
 * - batchEmbed 允许部分失败：它只算不存，成败互不影响，中断只会白烧额度；
 * - 本方法有失败就整批不写：一旦写入，Qdrant 里必须是这篇文档的完整一套。
 *   写一半的危害不在"少了几条"，而在检索质量悄悄劣化——用户问 RAG 时
 *   恰好漏掉那段关键内容，没有任何报错提示。宁可整批不落、失败明细如实报告，
 *   重跑一次幂等自愈（storeDocumentVectors 先删后写，重跑不会产生重复）。
 */
@Service
public class VectorizationService {

    private static final Logger log = LoggerFactory.getLogger(VectorizationService.class);

    private final DocumentService documentService;
    private final DocumentChunkMapper documentChunkMapper;
    private final EmbeddingService embeddingService;
    private final QdrantVectorStoreService qdrantVectorStoreService;

    public VectorizationService(DocumentService documentService,
                                 DocumentChunkMapper documentChunkMapper,
                                 EmbeddingService embeddingService,
                                 QdrantVectorStoreService qdrantVectorStoreService) {
        this.documentService = documentService;
        this.documentChunkMapper = documentChunkMapper;
        this.embeddingService = embeddingService;
        this.qdrantVectorStoreService = qdrantVectorStoreService;
    }

    /**
     * 给一篇文档的全部 Chunk 算向量并写入 Qdrant。入口约定与 Day 27 的
     * batchEmbed 一致：不加 Controller，从测试/未来调度入口调用。
     *
     * 维度断言为什么不在本方法做：嵌入的产物直接进 ChunkVector 交给存储层，
     * 由 QdrantVectorStoreService.toPoint() 在"组 Point"时统一校验——校验和
     * 使用放同一处，将来改维度配置只改一个地方。
     */
    public VectorizationResult embedAndStore(Long documentId) {
        // 1. 文档必须存在：不存在时 findById 自己抛 DocumentNotFoundException（→404）。
        documentService.findById(documentId);

        // 2. 取出全部块，chunk_index 升序。空列表合法（文档没切过块）：
        //    下面的循环一次不进，走"清掉旧向量、写入 0 条"的路径。
        List<DocumentChunk> chunks = documentChunkMapper.findByDocumentId(documentId);

        // 3. 幂等确保 Collection 存在（不存在则建，规格不符则抛错）。
        //    放在嵌入之前：库都不对就别白烧 API 额度了。
        qdrantVectorStoreService.ensureCollection();

        // 4. 逐块嵌入。失败收集不中断（先把成败看全），但本次的结果先攒着，
        //    有失败就整批不往 Qdrant 写（见类注释的失败策略）。
        int embeddedCount = 0;
        int dimensions = 0;
        List<ChunkVector> chunkVectors = new ArrayList<>(chunks.size());
        List<EmbeddingBatchResult.ChunkEmbeddingFailure> failures = new ArrayList<>();

        for (DocumentChunk chunk : chunks) {
            try {
                List<Float> vector = embeddingService.embed(chunk.getContent());
                chunkVectors.add(new ChunkVector(
                        chunk.getDocumentId(),
                        chunk.getId(),
                        chunk.getChunkIndex(),
                        vector));
                embeddedCount++;
                if (dimensions == 0) {
                    dimensions = vector.size();
                }
            } catch (RuntimeException e) {
                failures.add(new EmbeddingBatchResult.ChunkEmbeddingFailure(
                        chunk.getChunkIndex(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }

        // 5. 有失败 → 整批不落库，回执如实报告（storedPoints=0）。
        //    全成功（或文档本来就 0 块）→ 交给存储层"先删后写"。
        int storedPoints = 0;
        if (failures.isEmpty()) {
            storedPoints = qdrantVectorStoreService.storeDocumentVectors(documentId, chunkVectors);
            log.info("文档 {} 向量化完成：{} 块全部写入 Qdrant", documentId, storedPoints);
        } else {
            log.warn("文档 {} 向量化存在失败块（{}/{}），本次未写入 Qdrant：{}",
                    documentId, failures.size(), chunks.size(), failures);
        }

        return new VectorizationResult(
                documentId,
                chunks.size(),
                embeddedCount,
                failures.size(),
                storedPoints,
                dimensions,
                failures);
    }
}
