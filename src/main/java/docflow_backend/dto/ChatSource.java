package docflow_backend.dto;

/**
 * Day 30：回答的一个可追溯来源。
 *
 * 这些字段不是模型生成或从模型文本中解析出来的，而是 ChatService 直接从 Day 29 的
 * SearchResult 复制而来。因此 documentId/chunkId 始终指向本次真实检索到的 Chunk。
 * content 已经作为 Context 发给模型，不在响应中重复传输长文本。
 */
public record ChatSource(
        long documentId,
        long chunkId,
        int chunkIndex,
        float score) {
}
