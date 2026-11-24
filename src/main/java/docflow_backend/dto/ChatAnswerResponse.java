package docflow_backend.dto;

import java.util.List;

/**
 * Day 30：RAG 问答的结果。
 *
 * answer 是 Chat Model 的最终文字；sources 是本次实际提供给模型的检索依据，
 * 保持 SearchService 的相关度降序。两者同一次问答绑定返回，调用方无需额外查询。
 */
public record ChatAnswerResponse(String answer, List<ChatSource> sources) {
}
