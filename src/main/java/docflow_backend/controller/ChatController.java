package docflow_backend.controller;

import docflow_backend.dto.ChatAnswerResponse;
import docflow_backend.dto.ChatRequest;
import docflow_backend.service.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Day 31：RAG 问答的 HTTP 入口——把 Day 30 的 ChatService 暴露成 POST /api/chat。
 *
 * 和 DocumentController 一样，本类只做三件事：接收请求 → 调用业务 → 包装成功响应。
 * 一行业务逻辑都没有：
 * - 检索、Prompt 拼接、调模型全在 ChatService（Day 30，不改）；
 * - 空问题校验在 ChatService.answer() 入口（Day 30 已有，抛
 *   IllegalArgumentException → GlobalExceptionHandler → 400）；
 * - 模型调用失败抛 ChatModelCallException → GlobalExceptionHandler → 502。
 *
 * 返回值直接是 ChatAnswerResponse record：Jackson 自动把 answer / sources
 * 写成 JSON 字段。sources 由 ChatService 从真实检索结果复制而来，
 * Controller 不经手、不加工，自然不可能伪造来源。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 提交一个 RAG 问答。
     *
     * 请求体 JSON 形如：{"question": "...", "documentId": 20}
     * documentId 可选：不传 = 在所有已向量化的文档中检索。
     *
     * 成功永远 200（包括"检索没命中"——那是正常业务结果，返回"资料不足"+空 sources，
     * 不该用 5xx 表达"没找到"）。所有失败由异常通道统一翻译，本方法零分支。
     */
    @PostMapping
    public ChatAnswerResponse ask(@RequestBody ChatRequest request) {
        return chatService.answer(request);
    }
}
