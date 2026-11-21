package docflow_backend.dto;

/**
 * Day 29：语义检索的结果——一条"最相关的 Chunk"在调用方面前的样子。
 *
 * 它与 Qdrant 的 ScoredPoint 的区别（为什么不全用 ScoredPoint 透传）：
 * ScoredPoint 是 gRPC/protobuf 的传输对象，背着 getId/getPayloadMap/getVectors
 * 这些底层 API，且它只有向量库里存的三个 payload 字段（documentId/chunkId/chunkIndex），
 * 没有 Chunk 原文。调用方（未来的 RAG 拼接、Controller 返回）要的是"一段能直接读的文字 +
 * 它来自哪、相关到什么程度"，不是 protobuf 消息。所以 SearchService 在边界做一次翻译：
 * Qdrant 的 ScoredPoint + MySQL 的 DocumentChunk.content 合成这个干净的 record。
 *
 * 字段选择（与需求逐条对齐）：
 * - pointId   ：Qdrant Point ID（= chunkId，留着是为了调试/运维能直接定位 Qdrant 里的点）；
 * - chunkId   ：MySQL document_chunks.id，将来"再取相邻块""定位原文行"都靠它；
 * - documentId：来自 payload，知道这段话属于哪篇文档；
 * - chunkIndex：第几块（0 起），拼接上下文时按它排序；
 * - content   ：MySQL 回查出来的 Chunk 原文——这是 RAG 真正要喂给大模型的内容；
 * - score     ：Qdrant 算出的相似度分数（Cosine 距离下越大越相关），float 保留精度。
 *
 * 用 record：结果是一次性快照，不可变，没有行为——record 最贴合。
 * 排序不在它身上做：Qdrant 已按 score 降序返回，SearchService 保持那个顺序组装 List。
 */
public record SearchResult(
        long pointId,
        long chunkId,
        long documentId,
        int chunkIndex,
        String content,
        float score) {
}
