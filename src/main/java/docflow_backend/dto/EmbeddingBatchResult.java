package docflow_backend.dto;

import java.util.List;

/**
 * Day 27：批量向量化的回执——"给一篇文档的所有 Chunk 算了向量"这件事的统计结果。
 *
 * 为什么是一个 DTO 而不是直接返回 List&lt;float[]&gt;：
 * 批量处理的本质是"逐个尝试、允许部分失败"，调用方关心的不是每一个向量本身
 * （今天也不落库），而是"总共多少块、成了几块、败了几块、败在哪"。把统计和
 * 失败明细打包成一个对象，比抛异常或返回裸列表更清楚地表达这次批处理的结局。
 *
 * 字段职责：
 * - documentId：处理哪篇文档，调用方用来核对；
 * - totalChunks / successCount / failedCount：三张数一起看才完整。
 *   恒有 successCount + failedCount == totalChunks（每一块要么成要么败，不丢不重）；
 * - dimensions：成功向量化时的维度（配置是 1024）。0 表示"一个都没成功"，
 *   成功时填实际维度，让调用方能顺便确认模型返回的维度对不对——这是一次
 *   廉价的"维度体检"，不用额外跑校验逻辑；
 * - failures：失败明细（块序号 + 原因），空列表表示全成功。
 *   只记 chunkIndex 不记整块 content：定位问题够用，拖着正文浪费内存也难读。
 *
 * 注意：本 DTO 只在"只统计、不落库"阶段使用。将来向量真正落库后，
 * 这个回执可能演化为"返回存储位置/向量 ID"，届时再评估是否改名或增字段。
 */
public record EmbeddingBatchResult(
        Long documentId,
        int totalChunks,
        int successCount,
        int failedCount,
        int dimensions,
        List<ChunkEmbeddingFailure> failures
) {
    /**
     * 单个失败块的明细。用内嵌 record 而不是复用某个实体：
     * 它只表达"哪一块、因为什么没成"，是最小的失败切片，不背任何业务实体包袱。
     */
    public record ChunkEmbeddingFailure(
            int chunkIndex,
            String reason
    ) {
    }
}
