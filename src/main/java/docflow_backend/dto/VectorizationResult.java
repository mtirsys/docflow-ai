package docflow_backend.dto;

import java.util.List;

/**
 * Day 28：embedAndStore（批量向量化并落 Qdrant）的回执。
 *
 * 与 Day 27 的 EmbeddingBatchResult 的关系——同一件事的两个阶段：
 * - Day 27 batchEmbed：算向量、只统计、不落库 → 回执只关心"成了几块、败了几块"；
 * - Day 28 embedAndStore：算向量 + 真正写进 Qdrant → 回执必须多回答一个问题：
 *   "库里现在实际有几条"。storedPoints 就是这个数：调用方拿它和 totalChunks
 *   一对，立刻知道"算出来的"和"存进去的"是不是一样多。
 *
 * 字段口径：
 * - totalChunks：库里这篇文档有多少块（参与本次处理的分母）；
 * - embeddedCount / failedCount：算向量的成败二分，恒有两者之和 == totalChunks；
 * - storedPoints：最终 upsert 进 Qdrant 的 Point 数。设计约定"有任一块失败
 *   就整批不落库"，所以正常结局下要么 == embeddedCount（全成功落库），
 *   要么 == 0（有失败，本次未触碰 Qdrant）——不存在中间值；
 * - dimensions：成功时第一个向量的维度（1024），0 表示全失败；
 * - failures：失败明细（chunkIndex + 原因），复用 Day 27 的内嵌 record——
 *   它就是"哪一块、为什么没成"的最小切片，跨场景通用，不值得再造一个。
 */
public record VectorizationResult(
        Long documentId,
        int totalChunks,
        int embeddedCount,
        int failedCount,
        int storedPoints,
        int dimensions,
        List<EmbeddingBatchResult.ChunkEmbeddingFailure> failures
) {
}
