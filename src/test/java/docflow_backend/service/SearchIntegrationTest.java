package docflow_backend.service;

import docflow_backend.dto.SearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day 29 真实集成测试：用真实百炼 Embedding + 真实 Qdrant + 真实 MySQL，
 * 对 documentId=6（Day 28 补验收时种子进去的 3 个 chunk）做语义检索。
 *
 * 三条链路全真、零 mock——这是项目第一次端到端跑"问题→向量→相似度→原文"。
 * 单元测试（SearchServiceTest / QdrantVectorStoreServiceTest）已用 mock 覆盖
 * 分支逻辑；本类只验证"真链路能不能跑通、结果合不合理"。
 *
 * 前置条件（全部已在 Day 28 补验收时就绪）：
 * - Qdrant 已启动（6334 监听），doc_chunks Collection 存在（1024 维 Cosine）；
 * - MySQL document_chunks 表有 documentId=6 的 3 行（chunkId 1/2/3）；
 * - Qdrant doc_chunks 有对应 3 个 Point（payload 带 documentId/chunkId/chunkIndex）；
 * - 环境变量 DASHSCOPE_API_KEY / DB_PASSWORD 已设。
 *
 * 成本：每次到达 embed 的用例真实调用一次百炼 API（共约 6 次）。
 */
@Tag("integration")
@SpringBootTest
class SearchIntegrationTest {

    /** 种子文档 id（SeedDocumentIntegrationTest 生成、VectorizeDocumentIntegrationTest 推入 Qdrant）。 */
    private static final long DOCUMENT_ID = 6L;
    /** 该文档的 chunk 数（也是 Qdrant 里能命中的上限）。 */
    private static final int AVAILABLE_CHUNKS = 3;

    @Autowired
    private SearchService searchService;

    @Test
    @DisplayName("中文问题：返回相关 Chunk，分数降序，原文来自 MySQL，documentId 全=6")
    void chineseQueryReturnsRelevantChunks() {
        String query = "DocFlow 如何实现语义检索";
        List<SearchResult> results = searchService.search(query, DOCUMENT_ID, 3);

        assertFalse(results.isEmpty(), "中文问题应能命中至少 1 个 chunk");
        System.out.printf(">>> [中文] query=\"%s\", topK=3, documentId=%d%n", query, DOCUMENT_ID);
        printResults(results);

        // 1. 每条都有原文（MySQL 回查成功）
        // 2. 每条 documentId 都是 6（filter 生效）
        // 3. 分数降序（Qdrant 已排序，SearchService 保持）
        // 4. 分数在 (0, 1]（Cosine 相似度，归一化向量）
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            assertFalse(r.content().isBlank(), "第 " + i + " 条应有原文");
            assertEquals(DOCUMENT_ID, r.documentId(), "filter 应保证 documentId=6");
            assertTrue(r.score() > 0f && r.score() <= 1.001f,
                    "Cosine 分数应在 (0,1]，第 " + i + " 条 score=" + r.score());
            if (i > 0) {
                assertTrue(results.get(i - 1).score() >= r.score(),
                        "分数应降序：第 " + (i - 1) + " 条不应低于第 " + i + " 条");
            }
        }
    }

    @Test
    @DisplayName("英文问题：跨语言也能命中（百炼 embedding 支持中英语义对齐）")
    void englishQueryAlsoWorks() {
        String query = "vector similarity search";
        List<SearchResult> results = searchService.search(query, null, 3);

        assertFalse(results.isEmpty(), "英文问题应也能命中（百炼多语言 embedding）");
        System.out.printf(">>> [英文] query=\"%s\", topK=3, documentId=null(全库)%n", query);
        printResults(results);

        for (SearchResult r : results) {
            assertFalse(r.content().isBlank());
            assertTrue(r.score() > 0f);
        }
    }

    @Test
    @DisplayName("空问题：抛 IllegalArgumentException，不调百炼（省 API）")
    void emptyQueryRejected() {
        assertThrows(IllegalArgumentException.class, () -> searchService.search("   ", null, 5));
        assertThrows(IllegalArgumentException.class, () -> searchService.search("", null, 5));
        System.out.println(">>> [空问题] 抛 IllegalArgumentException，未调用百炼 API");
    }

    @Test
    @DisplayName("不存在的 documentId：filter 命中 0 个，返回空 List（不报错）")
    void nonExistentDocumentIdReturnsEmpty() {
        List<SearchResult> results = searchService.search("检索", 99999L, 5);

        assertTrue(results.isEmpty(), "不存在的 documentId 应返回空 List");
        System.out.println(">>> [不存在 docId=99999] 返回空 List（filter 命中 0 个，合法结果）");
    }

    @Test
    @DisplayName("topK=1 只返回 1 条；topK=10 被可用量(3)封顶，返回 3 条")
    void topKVariationsAndCapping() {
        List<SearchResult> one = searchService.search("Qdrant 向量库", null, 1);
        System.out.printf(">>> [topK=1] 返回 %d 条%n", one.size());
        assertTrue(one.size() <= 1, "topK=1 最多 1 条");
        assertFalse(one.isEmpty(), "库里有点，topK=1 应返回 1 条");

        List<SearchResult> ten = searchService.search("Qdrant 向量库", null, 10);
        System.out.printf(">>> [topK=10] 返回 %d 条（被可用量 %d 封顶）%n",
                ten.size(), AVAILABLE_CHUNKS);
        assertTrue(ten.size() <= AVAILABLE_CHUNKS, "topK>可用量时不应超过库里实际点数");
        assertEquals(AVAILABLE_CHUNKS, ten.size(), "库里 3 个点，topK=10 应返回全部 3 个");

        // 两次最相关的应是同一条（topK=1 的那条 == topK=10 的第一条）
        if (!one.isEmpty() && !ten.isEmpty()) {
            assertEquals(one.get(0).chunkId(), ten.get(0).chunkId(),
                    "topK=1 的结果应与 topK=10 的第一条一致（都是最相关）");
            System.out.printf("    两次最相关一致：chunkId=%d, score=%.4f%n",
                    one.get(0).chunkId(), one.get(0).score());
        }
    }

    /** 打印每条结果：pointId/chunkId/documentId/chunkIndex/score + 原文预览。 */
    private void printResults(List<SearchResult> results) {
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            String preview = r.content().substring(0, Math.min(40, r.content().length()))
                    .replace('\n', ' ');
            System.out.printf("    #%d pointId=%d | chunkId=%d | documentId=%d | chunkIndex=%d | "
                            + "score=%.4f | \"%s...\"%n",
                    i + 1, r.pointId(), r.chunkId(), r.documentId(),
                    r.chunkIndex(), r.score(), preview);
        }
    }
}
