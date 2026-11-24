package docflow_backend.service;

import docflow_backend.dto.ChatAnswerResponse;
import docflow_backend.dto.ChatRequest;
import docflow_backend.dto.ChatSource;
import docflow_backend.mapper.DocumentChunkMapper;
import docflow_backend.model.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Day 30：真实 RAG 问答集成测试。
 *
 * 此类会真实调用百炼 Embedding 和 Chat API，也会连接本地 MySQL/Qdrant；因为标注了
 * integration，pom.xml 默认排除它，普通 mvn test 不会消耗 API 额度。
 *
 * 前置条件与 Day 29 SearchIntegrationTest 一致：
 * - MySQL、Qdrant 均已启动；
 * - 文档 6 已有 3 个 Chunk，且其向量已经写入 Qdrant；
 * - 当前启动 Maven 的进程已继承 DASHSCOPE_API_KEY、BAILIAN_WORKSPACE_ID、DB_PASSWORD。
 *
 * 单独运行：
 * .\mvnw.cmd test "-Dtest=ChatServiceIntegrationTest" -DfailIfNoTests=false
 *     "-Dsurefire.excludedGroups="
 */
@Tag("integration")
@SpringBootTest
class ChatServiceIntegrationTest {

    private static final long DOCUMENT_ID = 6L;

    @Autowired
    private ChatService chatService;

    @Autowired
    private DocumentChunkMapper documentChunkMapper;

    @Test
    @DisplayName("真实百炼 RAG：问题经检索与 Context 后生成回答，所有来源均可回查 MySQL")
    void answersWithRealModelAndRealSources() {
        ChatAnswerResponse response = chatService.answer(new ChatRequest(
                "DocFlow 如何实现语义检索？请根据文档简洁说明。", DOCUMENT_ID));

        System.out.printf(">>> Day 30 RAG answer: %s%n", response.answer());
        response.sources().forEach(source -> System.out.printf(
                ">>> source: documentId=%d, chunkId=%d, chunkIndex=%d, score=%.4f%n",
                source.documentId(), source.chunkId(), source.chunkIndex(), source.score()));

        assertFalse(response.answer().isBlank(), "真实 Chat Model 应返回非空最终回答");
        assertFalse(response.sources().isEmpty(), "已有向量化文档时应返回至少一个真实来源");
        for (ChatSource source : response.sources()) {
            assertEquals(DOCUMENT_ID, source.documentId(), "documentId filter 应生效");
        }

        // 独立回查 MySQL：不只信任 ChatService 组装的 DTO，逐项验证 source 真有对应 Chunk 行。
        List<Long> sourceChunkIds = response.sources().stream().map(ChatSource::chunkId).toList();
        List<DocumentChunk> chunks = documentChunkMapper.findByIds(sourceChunkIds);
        assertEquals(sourceChunkIds.size(), chunks.size(), "每个返回 source 都必须能在 MySQL 找到真实 Chunk");
    }
}
