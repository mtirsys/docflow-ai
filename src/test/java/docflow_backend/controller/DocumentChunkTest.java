package docflow_backend.controller;

import docflow_backend.exception.GlobalExceptionHandler;
import docflow_backend.mapper.DocumentChunkMapper;
import docflow_backend.mapper.DocumentMapper;
import docflow_backend.model.Document;
import docflow_backend.service.DocumentService;
import docflow_backend.storage.LocalFileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 24：POST /api/documents/{id}/chunks 的 HTTP 契约测试
 * （MockMvc standalone，不起服务、不连数据库，同既有 Controller 测试风格）。
 *
 * 只测 HTTP 层的分内事：
 * - 成功 → 201 + 回执 JSON（documentId / totalChunks / totalChars）；
 * - 文档不存在 → GlobalExceptionHandler 把 DocumentNotFoundException 翻成 404。
 * 切块逻辑、落库顺序的对错是 DocumentChunkServiceTest 的职责，这里不重复。
 */
class DocumentChunkTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private DocumentMapper documentMapper;
    private DocumentChunkMapper documentChunkMapper;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        documentChunkMapper = mock(DocumentChunkMapper.class);
        DocumentService documentService = new DocumentService(
                documentMapper, documentChunkMapper,
                new LocalFileStorageService(tempDir.toString()),
                new docflow_backend.parser.PdfTextExtractor(),
                new docflow_backend.parser.DocxTextExtractor(),
                new docflow_backend.parser.TextContentCleaner(),
                new docflow_backend.parser.TextChunker());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentController(documentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("分块保存成功：返回 201 和回执 JSON")
    void chunkEndpointReturns201WithReceipt() throws Exception {
        long id = 15L;
        seedMarkdown(id, "chunk-ok.md",
                "甲".repeat(300) + "\n\n" + "乙".repeat(300) + "\n\n" + "丙".repeat(300));
        given(documentChunkMapper.insertBatch(anyList())).willReturn(3);

        mockMvc.perform(post("/api/documents/{id}/chunks", id))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentId").value(15))
                .andExpect(jsonPath("$.totalChunks").value(3));
    }

    @Test
    @DisplayName("文档不存在：走既有 404 通道，Controller 零分支")
    void chunkEndpointReturns404ForMissingDocument() throws Exception {
        when(documentMapper.findById(999L)).thenReturn(null);

        mockMvc.perform(post("/api/documents/{id}/chunks", 999L))
                .andExpect(status().isNotFound());
    }

    /** 造一个"已上传"的 MD 文件和对应的数据库记录形态。 */
    private void seedMarkdown(long id, String filename, String content) throws Exception {
        Files.writeString(tempDir.resolve(filename), content, StandardCharsets.UTF_8);

        Document document = new Document();
        document.setId(id);
        document.setTitle(filename);
        document.setOriginalFilename(filename);
        document.setFileType("MD");
        document.setStorageKey(filename);
        document.setStatus("UPLOADED");
        when(documentMapper.findById(id)).thenReturn(document);
    }
}
