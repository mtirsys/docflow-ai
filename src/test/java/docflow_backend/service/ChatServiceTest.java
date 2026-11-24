package docflow_backend.service;

import docflow_backend.dto.ChatAnswerResponse;
import docflow_backend.dto.ChatRequest;
import docflow_backend.dto.ChatSource;
import docflow_backend.dto.SearchResult;
import docflow_backend.exception.ChatModelCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Day 30：ChatService 的纯单元测试。
 *
 * SearchService 与 ChatModel 均为 mock，所以不访问 MySQL、Qdrant 或百炼，
 * 不消耗 API 额度。测试目标是 ChatService 自己的职责：复用检索结果、组装
 * Prompt、调用聊天模型、保留真实来源，以及清晰区分各类失败。
 */
class ChatServiceTest {

    private SearchService searchService;
    private ChatModel chatModel;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        searchService = mock(SearchService.class);
        chatModel = mock(ChatModel.class);
        chatService = new ChatService(searchService, chatModel);
    }

    /** 用 Spring AI 2.0.2 的真实响应对象造一个模型回答，避免 mock 框架响应链。 */
    private ChatResponse modelResponse(String answer) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    private SearchResult result(long documentId, long chunkId, int chunkIndex, String content, float score) {
        return new SearchResult(chunkId, chunkId, documentId, chunkIndex, content, score);
    }

    @Test
    @DisplayName("有检索结果：原文进入 Prompt，模型回答与真实来源一起返回")
    void answerUsesSearchContextAndReturnsRealSources() {
        String question = "DocFlow 如何从 Qdrant 得到 Chunk 原文？";
        List<SearchResult> results = List.of(
                result(8L, 101L, 0, "Qdrant 返回 chunkId 后，批量查询 MySQL 获取 content。", 0.93f),
                result(8L, 102L, 1, "SearchService 保持 Qdrant 的分数降序。", 0.87f));
        when(searchService.search(question, 8L, ChatService.RAG_TOP_K)).thenReturn(results);
        when(chatModel.call(any(Prompt.class))).thenReturn(modelResponse(
                "Qdrant 返回 chunkId 后，服务会批量查询 MySQL 获得原文。[D8-C101]"));

        ChatAnswerResponse response = chatService.answer(new ChatRequest(question, 8L));

        assertEquals("Qdrant 返回 chunkId 后，服务会批量查询 MySQL 获得原文。[D8-C101]", response.answer());
        assertEquals(List.of(
                new ChatSource(8L, 101L, 0, 0.93f),
                new ChatSource(8L, 102L, 1, 0.87f)), response.sources());

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();
        assertTrue(prompt.getSystemMessage().getText().contains("只能引用 Context 中实际出现过的来源标记"));
        assertTrue(prompt.getUserMessage().getText().contains(question));
        assertTrue(prompt.getUserMessage().getText().contains("[来源 D8-C101，第 0 块，相关度 0.93]"));
        assertTrue(prompt.getUserMessage().getText().contains("批量查询 MySQL 获取 content"));
        assertTrue(prompt.getUserMessage().getText().contains("[来源 D8-C102，第 1 块，相关度 0.87]"));
        verify(searchService).search(question, 8L, ChatService.RAG_TOP_K);
    }

    @Test
    @DisplayName("部分相关：模型只能看到检索到的片段，来源仍由 SearchResult 直接转换")
    void partiallyRelatedQuestionUsesOnlyRetrievedContext() {
        String question = "文档是否说明了向量维度为什么是 1024？";
        SearchResult partialEvidence = result(6L, 9L, 2,
                "Qdrant Collection 的 vector-dimension 配置引用 embedding.dimensions。", 0.81f);
        when(searchService.search(question, null, ChatService.RAG_TOP_K)).thenReturn(List.of(partialEvidence));
        when(chatModel.call(any(Prompt.class))).thenReturn(modelResponse(
                "资料只说明两个配置关联，未说明选择 1024 的完整原因。[D6-C9]"));

        ChatAnswerResponse response = chatService.answer(new ChatRequest(question, null));

        assertEquals(1, response.sources().size());
        assertEquals(new ChatSource(6L, 9L, 2, 0.81f), response.sources().getFirst());
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        String context = promptCaptor.getValue().getUserMessage().getText();
        assertTrue(context.contains(partialEvidence.content()));
        assertTrue(context.contains("D6-C9"));
    }

    @Test
    @DisplayName("没有检索结果：返回资料不足，不调用 Chat Model")
    void noSearchResultReturnsInsufficientInformationWithoutChatCall() {
        String question = "文档中不存在的主题";
        when(searchService.search(question, 99999L, ChatService.RAG_TOP_K)).thenReturn(List.of());

        ChatAnswerResponse response = chatService.answer(new ChatRequest(question, 99999L));

        assertEquals(ChatService.INSUFFICIENT_INFORMATION_ANSWER, response.answer());
        assertTrue(response.sources().isEmpty());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("空问题或空请求：在检索和 Chat 调用前拒绝")
    void blankQuestionRejectedBeforeSearchAndChat() {
        assertThrows(IllegalArgumentException.class, () -> chatService.answer(null));
        assertThrows(IllegalArgumentException.class, () -> chatService.answer(new ChatRequest(null, null)));
        assertThrows(IllegalArgumentException.class, () -> chatService.answer(new ChatRequest("   ", null)));

        verify(searchService, never()).search(any(), any(), anyInt());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("Chat API 抛异常：包装为 ChatModelCallException，不伪装成资料不足")
    void chatApiFailureIsTranslated() {
        String question = "检索后生成失败怎么办？";
        when(searchService.search(question, 6L, ChatService.RAG_TOP_K)).thenReturn(List.of(
                result(6L, 1L, 0, "这是已检索到的真实 Context。", 0.9f)));
        IllegalStateException apiFailure = new IllegalStateException("HTTP 503");
        when(chatModel.call(any(Prompt.class))).thenThrow(apiFailure);

        ChatModelCallException exception = assertThrows(ChatModelCallException.class,
                () -> chatService.answer(new ChatRequest(question, 6L)));

        assertEquals("聊天模型调用失败，请稍后重试", exception.getMessage());
        assertEquals(apiFailure, exception.getCause());
    }

    @Test
    @DisplayName("Chat Model 返回空内容或空响应：明确抛 ChatModelCallException")
    void emptyChatModelResponseIsRejected() {
        String question = "模型空回答";
        when(searchService.search(eq(question), eq(6L), eq(ChatService.RAG_TOP_K))).thenReturn(List.of(
                result(6L, 1L, 0, "真实 Context", 0.9f)));
        when(chatModel.call(any(Prompt.class))).thenReturn(modelResponse("   "));

        ChatModelCallException emptyText = assertThrows(ChatModelCallException.class,
                () -> chatService.answer(new ChatRequest(question, 6L)));
        assertEquals("聊天模型返回了空内容", emptyText.getMessage());

        when(chatModel.call(any(Prompt.class))).thenReturn(null);
        ChatModelCallException nullResponse = assertThrows(ChatModelCallException.class,
                () -> chatService.answer(new ChatRequest(question, 6L)));
        assertEquals("聊天模型未返回有效响应", nullResponse.getMessage());
    }
}
