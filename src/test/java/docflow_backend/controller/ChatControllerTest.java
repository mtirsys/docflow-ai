package docflow_backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import docflow_backend.dto.ChatRequest;
import docflow_backend.dto.SearchResult;
import docflow_backend.exception.GlobalExceptionHandler;
import docflow_backend.service.ChatService;
import docflow_backend.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 31：POST /api/chat 的 HTTP 契约测试（MockMvc standalone，同 DocumentChunkTest 风格：
 * 不起 Spring 上下文、不连数据库，只挂 Controller + GlobalExceptionHandler）。
 *
 * 分层策略（与 DocumentChunkTest"真 Service + mock Mapper"同思路，再往深一层）：
 * - ChatService 用真的：空 question 的 400、"资料不足"的 200、sources 的组装，
 *   走的都是 Day 30 的真实代码——不是 mock 替它演的；
 * - SearchService / ChatModel mock 掉：检索和大模型调用是 Day 29/30 已验收的能力，
 *   这里只验证"HTTP 层把参数原样传下去、把结果原样递回来"。
 *
 * Mock 测试不冒充真实 API 测试：真实百炼 + MySQL + Qdrant 的全链路验证
 * 由 Day 30 的 ChatServiceIntegrationTest（@Tag("integration")）负责。
 */
class ChatControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MockMvc mockMvc;
    private SearchService searchService;
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        searchService = mock(SearchService.class);
        chatModel = mock(ChatModel.class);
        ChatService chatService = new ChatService(searchService, chatModel);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(chatService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 用 Spring AI 2.0.2 的真实响应对象造一个模型回答，避免 mock 框架响应链。 */
    private ChatResponse modelResponse(String answer) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    private SearchResult result(long documentId, long chunkId, int chunkIndex, String content, float score) {
        return new SearchResult(chunkId, chunkId, documentId, chunkIndex, content, score);
    }

    @Test
    @DisplayName("正常问答：200 + answer + sources，sources 逐字段等于真实检索结果")
    void askReturnsAnswerAndSourcesFromSearchResults() throws Exception {
        String question = "DocFlow 如何实现语义检索？";
        // RAG_TOP_K 是 ChatService 的包私有常量，值为 5（Day 30 已验收），
        // controller 包访问不到，这里用字面量 5 表达同一契约。
        when(searchService.search(question, 8L, 5)).thenReturn(List.of(
                result(8L, 101L, 0, "Qdrant 返回 chunkId 后，批量查询 MySQL 获取 content。", 0.93f),
                result(8L, 102L, 1, "SearchService 保持 Qdrant 的分数降序。", 0.87f)));
        when(chatModel.call(any(Prompt.class))).thenReturn(modelResponse(
                "Qdrant 返回 chunkId 后，服务会批量查询 MySQL 获得原文。[D8-C101]"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new ChatRequest(question, 8L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(
                        "Qdrant 返回 chunkId 后，服务会批量查询 MySQL 获得原文。[D8-C101]"))
                // sources 逐字段断言：响应里的每个值都必须来自上面 stub 的 SearchResult，
                // 证明 Controller/Service 只是"透传检索结果"，没有加工也没有凭空生成。
                .andExpect(jsonPath("$.sources.length()").value(2))
                .andExpect(jsonPath("$.sources[0].documentId").value(8))
                .andExpect(jsonPath("$.sources[0].chunkId").value(101))
                .andExpect(jsonPath("$.sources[0].chunkIndex").value(0))
                .andExpect(jsonPath("$.sources[0].score").value(0.93))
                .andExpect(jsonPath("$.sources[1].documentId").value(8))
                .andExpect(jsonPath("$.sources[1].chunkId").value(102))
                .andExpect(jsonPath("$.sources[1].score").value(0.87));

        // Controller 必须把 question 和 documentId 原样传给 SearchService。
        verify(searchService).search(question, 8L, 5);
    }

    @Test
    @DisplayName("question 缺失（null）：走真实 ChatService 校验，返回 400")
    void missingQuestionReturns400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\": 6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("问题不能为空：空问题无法进行检索和问答"))
                .andExpect(jsonPath("$.path").value("/api/chat"));

        verify(searchService, never()).search(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("question 只有空格：isBlank 同样拦下，返回 400")
    void whitespaceOnlyQuestionReturns400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new ChatRequest("   ", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("问题不能为空：空问题无法进行检索和问答"));

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("documentId 不存在 / 检索无结果：200 + 资料不足 + 空 sources，不调模型")
    void noSearchResultReturnsInsufficientInformation() throws Exception {
        // 两条用户场景走同一条真实代码路径：不存在的 documentId 让 Qdrant filter
        // 命中 0 个、空库检索，SearchService 都返回空 List——所以一个用例覆盖两者。
        String question = "文档中不存在的主题";
        when(searchService.search(question, 99999L, 5)).thenReturn(List.of());

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new ChatRequest(question, 99999L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(
                        "资料不足：提供的文档片段没有足够信息回答这个问题。"))
                .andExpect(jsonPath("$.sources.length()").value(0));

        // "没证据"不该烧模型额度：ChatService 的真实分支保证不调 ChatModel。
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("大模型调用失败：ChatModelCallException → 502 + 通用提示，不泄露细节")
    void chatModelFailureReturns502() throws Exception {
        String question = "模型挂了会返回什么？";
        when(searchService.search(question, 6L, 5)).thenReturn(List.of(
                result(6L, 1L, 0, "这是已检索到的真实 Context。", 0.9f)));
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("HTTP 503 Service Unavailable"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new ChatRequest(question, 6L))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                // 原始报错 "HTTP 503..." 只进日志，绝不出现在响应里。
                .andExpect(jsonPath("$.message").value("问答模型暂不可用，请稍后重试"))
                .andExpect(jsonPath("$.path").value("/api/chat"));
    }

    @Test
    @DisplayName("JSON 语法错误：HttpMessageNotReadableException → 400")
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式错误：请检查 JSON 语法和字段类型"));
    }
}
