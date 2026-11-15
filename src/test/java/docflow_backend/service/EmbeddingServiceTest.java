package docflow_backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Day 25：EmbeddingService 单元测试。
 *
 * 用 Mockito mock 掉 EmbeddingModel（Spring AI 的模型客户端）：
 * 不联网、不消耗 API 额度、毫秒级跑完——日常 mvnw test 随便跑。
 *
 * 真实百炼调用属于集成验证，故意不写进自动化测试（避免每次构建都花钱）。
 * 本测试验证的是"我们这层壳"的职责：文本透传、空文本拦截、异常不吞。
 */
class EmbeddingServiceTest {

    /** 维度按 application.properties 的 dimensions=1024 配置来。 */
    private static final int DIMENSIONS = 1024;

    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final EmbeddingService embeddingService = new EmbeddingService(embeddingModel);

    @Test
    void chineseTextIsPassedToModelAndVectorReturned() {
        String text = "DocFlow AI 是一个文档问答系统";
        float[] fakeVector = new float[DIMENSIONS];
        when(embeddingModel.embed(text)).thenReturn(fakeVector);

        float[] result = embeddingService.embed(text);

        // 模型给什么就原样交付（我们不加工向量）
        assertSame(fakeVector, result);
        // 原文一个字不改地透传给模型
        verify(embeddingModel).embed(text);
    }

    @Test
    void englishTextIsPassedToModelAndVectorReturned() {
        String text = "DocFlow AI is a document QA system";
        float[] fakeVector = new float[DIMENSIONS];
        when(embeddingModel.embed(text)).thenReturn(fakeVector);

        float[] result = embeddingService.embed(text);

        assertSame(fakeVector, result);
        verify(embeddingModel).embed(text);
    }

    @Test
    void nullTextIsRejectedBeforeCallingModel() {
        assertThrows(IllegalArgumentException.class, () -> embeddingService.embed(null));
        // 拦截必须发生在本地：一次 API 都不该发出去（null 连 Spring AI 的 Assert 都能拦，
        // 但我们这层先拦，语义消息更清楚）
        verifyNoInteractions(embeddingModel);
    }

    @Test
    void emptyTextIsRejectedBeforeCallingModel() {
        // 空字符串是 Spring AI 不拦的坑（embed(String) 只拦 null），必须由我们这层拦
        assertThrows(IllegalArgumentException.class, () -> embeddingService.embed(""));
        verifyNoInteractions(embeddingModel);
    }

    @Test
    void blankTextIsRejectedBeforeCallingModel() {
        // 纯空格也是"没有语义的文本"，同样拦在本地
        assertThrows(IllegalArgumentException.class, () -> embeddingService.embed("   "));
        verifyNoInteractions(embeddingModel);
    }

    @Test
    void returnedVectorIsNotNull() {
        float[] fakeVector = new float[DIMENSIONS];
        fakeVector[0] = 0.0123f;
        when(embeddingModel.embed(anyString())).thenReturn(fakeVector);

        float[] result = embeddingService.embed("任意一段文本");

        assertNotNull(result, "向量不应为 null");
        assertEquals(DIMENSIONS, result.length);
    }

    @Test
    void vectorDimensionsArePassedThroughUnchanged() {
        // mock 模型返回 1024 维，验证我们的壳不砍也不加维度。
        // 注意：真实百炼是否真给 1024 维，属于集成验证——单元测试只担保"我们不搞破坏"。
        when(embeddingModel.embed(anyString())).thenReturn(new float[DIMENSIONS]);

        float[] result = embeddingService.embed("维度检查");

        assertEquals(DIMENSIONS, result.length);
    }

    @Test
    void modelFailurePropagatesUnchanged() {
        // 底层 API 挂了（网络/超时/Key 错），异常必须原样往外抛——
        // 我们这层不吞、不包、不打折，让调用方拿到真实失败信号
        RuntimeException apiFailure = new RuntimeException("模拟：百炼调用失败");
        when(embeddingModel.embed(anyString())).thenThrow(apiFailure);

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> embeddingService.embed("会触发失败调用"));

        assertSame(apiFailure, thrown);
    }
}
