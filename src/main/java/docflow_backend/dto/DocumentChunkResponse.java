package docflow_backend.dto;

/**
 * Day 24：POST /api/documents/{id}/chunks 的 Service 层产出——"分块已保存"的回执。
 *
 * 字段取舍（同 DocumentContentResponse 的思路，Service 只交业务包裹）：
 * - documentId：这批块属于哪篇文档，客户端用来定位和后续查询；
 * - totalChunks：实际保存了多少块（等于最后一块的 chunkIndex + 1）；
 * - totalChars：全部块字符数之和，客户端展示"共切出 N 块 / M 字"，
 *   也方便测试断言"一块不丢"；
 * - 刻意不返回每块的完整 content：回执只需要"干成了、干成什么样"，
 *   块的正文将来由检索/向量化接口按需取，回执里拖着全部正文是浪费带宽。
 */
public record DocumentChunkResponse(
        Long documentId,
        int totalChunks,
        long totalChars
) {
}
