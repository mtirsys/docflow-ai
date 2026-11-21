package docflow_backend.service;

import docflow_backend.dto.VectorizationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Day 28 补课（2026-11-21）：真实链路推 Qdrant——把 MySQL 里已分块的文档
 * 逐块算成真实向量并写入 Qdrant 生产 Collection（doc_chunks）。
 *
 * 这是整个项目第一次用真实百炼 API 算向量（Day 26/27/28 全是 mock），
 * 也是第一次真实回答这个问题：qwen3.7-text-embedding-flash 配置 dimensions=1024，
 * 实际返回的向量到底是多少维？答案由本测试的维度断言给出。
 *
 * 编排全部复用 Day 28 既有代码（本类不含任何业务逻辑），
 * VectorizationService.embedAndStore(documentId) 内部依次做——
 *   1. findById(6)：文档存在性检查（不存在 → DocumentNotFoundException）；
 *   2. findByDocumentId(6)：取回 3 个 chunk（chunk_index 升序）；
 *   3. ensureCollection()：doc_chunks 不存在则自动创建（1024 维、Cosine）；
 *      维度/距离与配置不符则抛错——不会默默写脏数据；
 *   4. 逐块 embeddingService.embed(content)：3 次真实百炼 API 调用；
 *   5. storeDocumentVectors：按 documentId 先删旧 Point、再整批 upsert——
 *      Point ID = chunk.id（MySQL 自增主键），payload 带 documentId/chunkId/chunkIndex。
 *
 * 失败策略（Day 28 设计）：任一块嵌入失败则整批不落库、storedPoints=0，
 * 失败明细在回执的 failures 里——不会出现"写了半批"的脏数据。
 *
 * 重复执行安全：先删后写幂等，重跑不产生重复 Point（但会再烧一次 API 额度）。
 *
 * 运行前提（缺一不可）：
 *   1. Qdrant 已启动（gRPC 6334 监听；启动方式：cd C:\Users\ye\qdrant-local && qdrant.exe，
 *      窗口保持开着，关窗口 = 关服务）；
 *   2. MySQL 已启动，document_chunks 表已建，文档 6 已分块（3 块）；
 *   3. 环境变量 DASHSCOPE_API_KEY / BAILIAN_WORKSPACE_ID / DB_PASSWORD 已设置
 *      （mvn 进程继承当前 shell 的环境变量，无需额外操作）。
 *
 * 运行方式（docflow-backend 目录）：
 *   mvn.cmd test -Dtest=VectorizeDocumentIntegrationTest -DfailIfNoTests=false "-DexcludedGroups="
 */
@Tag("integration")
@SpringBootTest
class VectorizeDocumentIntegrationTest {

    /** 种子文档 id（SeedDocumentIntegrationTest 实际生成的）。重新播种后改这一行。 */
    private static final Long DOCUMENT_ID = 6L;

    /** 种子文档的块数。改了种子内容或重跑分块后，按实际值改这一行。 */
    private static final int EXPECTED_CHUNKS = 3;

    @Autowired
    private VectorizationService vectorizationService;

    /** 只为"眼见为实"的独立复核：写入后绕回 Qdrant 数一遍 Point 数。 */
    @Autowired
    private QdrantVectorStoreService qdrantVectorStoreService;

    @Test
    @DisplayName("真实百炼向量化并落 Qdrant：3 块全部写入 doc_chunks，维度首次实测")
    void embedAndStoreRealDocument() {
        // 1. 真实编排一次跑完：查块 → 确保 Collection → 3 次真实百炼调用 → 先删后写。
        //    任何一步失败都会以异常冒出（QdrantStorageException / IllegalStateException），
        //    测试直接红，本类不需要自己造错误处理。
        VectorizationResult result = vectorizationService.embedAndStore(DOCUMENT_ID);

        System.out.printf(">>> embedAndStore 回执：documentId=%d, totalChunks=%d, embeddedCount=%d, "
                        + "failedCount=%d, storedPoints=%d, dimensions=%d%n",
                result.documentId(), result.totalChunks(), result.embeddedCount(),
                result.failedCount(), result.storedPoints(), result.dimensions());
        result.failures().forEach(f ->
                System.out.printf(">>> 失败块：chunkIndex=%d, 原因=%s%n", f.chunkIndex(), f.reason()));

        // 2. 回执断言：全成功、整批落库（Day 28 的约定：storedPoints 不存在中间值）。
        assertEquals(DOCUMENT_ID, result.documentId());
        assertEquals(EXPECTED_CHUNKS, result.totalChunks(), "MySQL 里应有 3 个 chunk");
        assertEquals(0, result.failedCount(), "不应有嵌入失败块（有失败则整批未落库，见 failures 明细）");
        assertEquals(EXPECTED_CHUNKS, result.embeddedCount());
        assertEquals(EXPECTED_CHUNKS, result.storedPoints(),
                "storedPoints 要么等于全成功数、要么为 0——出现中间值说明设计被破坏");

        // 3. 维度断言——本测试的历史使命：项目首次用真实 API 实测百炼维度。
        //    若配置的 1024 与真实返回不符，第 1 步里 toPoint 的维度断言会先拦下
        //    （IllegalStateException，中文明说），根本走不到这里；走到这里，
        //    dimensions() 就是百炼真实返回的维度。
        assertEquals(1024, result.dimensions(),
                "真实百炼维度首次实测：期望 1024，不符说明模型与配置不一致");

        // 4. 眼见为实：绕回 Qdrant 独立数一遍（不信任第 1 步的回执，直接问库）。
        //    先删后写的幂等性也在这里体现：重复执行本测试，这个数仍是 3、不会翻倍。
        long pointsInQdrant = qdrantVectorStoreService.countByDocumentId(DOCUMENT_ID);
        System.out.printf(">>> Qdrant 实点数（countByDocumentId）：documentId=%d, count=%d%n",
                DOCUMENT_ID, pointsInQdrant);
        assertEquals(EXPECTED_CHUNKS, pointsInQdrant,
                "Qdrant 里 documentId=6 应恰好 3 个 Point——多一个=有重复，少一个=有丢块");

        System.out.printf(">>> Day 28 真实链路打通：文档 %d 的 %d 个块已带着真实向量写入 Qdrant。%n",
                DOCUMENT_ID, pointsInQdrant);
    }
}
