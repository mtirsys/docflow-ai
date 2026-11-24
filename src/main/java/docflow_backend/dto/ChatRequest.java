package docflow_backend.dto;

/**
 * Day 30：一次 RAG 问答的输入。
 *
 * documentId 为 null 时沿用 SearchService 的既有语义：在所有已向量化的文档中检索；
 * 非 null 时只在指定文档中检索。问题是否为空由 ChatService 校验，避免 DTO 既承担
 * HTTP 校验又承担 Service 校验——本期没有 Controller，Service 才是实际入口。
 */
public record ChatRequest(String question, Long documentId) {
}
