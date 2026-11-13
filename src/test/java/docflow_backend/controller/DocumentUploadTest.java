package docflow_backend.controller;

import com.jayway.jsonpath.JsonPath;
import docflow_backend.exception.GlobalExceptionHandler;
import docflow_backend.mapper.DocumentMapper;
import docflow_backend.model.Document;
import docflow_backend.service.DocumentService;
import docflow_backend.storage.LocalFileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 15：POST /api/documents/upload 的自动化测试。
 *
 * 装配方式和 UploadCheckTest 一样：真实 DocumentService + mock 的 Mapper，
 * 区别是这里还用"指向 @TempDir 的真实 LocalFileStorageService"——
 * 因为被测的重点就是"校验通过之后文件真的落盘了"。
 * 数据库依然全程不参与（今天不写 documents 记录）。
 */
class DocumentUploadTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private DocumentMapper documentMapper;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        LocalFileStorageService storageService = new LocalFileStorageService(tempDir.toString());
        DocumentService documentService = new DocumentService(
                documentMapper, mock(docflow_backend.mapper.DocumentChunkMapper.class), storageService,
                new docflow_backend.parser.PdfTextExtractor(),
                new docflow_backend.parser.DocxTextExtractor(), new docflow_backend.parser.TextContentCleaner(),
                new docflow_backend.parser.TextChunker());
        when(documentMapper.insert(org.mockito.ArgumentMatchers.any(Document.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, Document.class).setId(101L);
                    return 1;
                });

        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentController(documentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("合法 PDF：201 + stored=true + storageKey，磁盘上能按 key 找到相同内容的文件")
    void uploadValidPdfPersistsFile() throws Exception {
        byte[] content = "%PDF-1.4 real upload".getBytes(StandardCharsets.UTF_8);

        String body = mockMvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile(
                                "file", "季度报告.pdf", "application/pdf", content)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.originalFilename").value("季度报告.pdf"))
                .andExpect(jsonPath("$.size").value(content.length))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.stored").value(true))
                .andExpect(jsonPath("$.storageKey").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String storageKey = JsonPath.read(body, "$.storageKey");
        Path stored = tempDir.resolve(storageKey);
        assertTrue(Files.exists(stored), "响应里的 storageKey 必须能在存储目录找到文件");
        assertArrayEquals(content, Files.readAllBytes(stored));
        assertFalse(storageKey.contains("季度报告"), "storageKey 不能来自用户文件名");

        ArgumentCaptor<Document> document = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).insert(document.capture());
        assertEquals("季度报告.pdf", document.getValue().getTitle());
        assertEquals("PDF", document.getValue().getFileType());
        assertEquals("季度报告.pdf", document.getValue().getOriginalFilename());
        assertEquals((long) content.length, document.getValue().getFileSize());
        assertEquals(storageKey, document.getValue().getStorageKey());
        assertEquals("UPLOADED", document.getValue().getStatus());
    }

    @Test
    @DisplayName("空文件：复用 upload-check 的校验返回 400，且存储目录没有产生任何文件")
    void uploadEmptyFileReturns400AndStoresNothing() throws Exception {
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile(
                                "file", "empty.pdf", "application/pdf", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("上传文件为空：请选择一个非空文件"));

        assertStorageDirIsEmpty();
    }

    @Test
    @DisplayName("不支持的扩展名 .exe：400，不落盘")
    void uploadUnsupportedExtensionReturns400() throws Exception {
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile(
                                "file", "virus.exe", "application/octet-stream", "x".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不支持的文件类型：只允许 PDF、DOCX、MD，收到的是 .exe"));

        assertStorageDirIsEmpty();
    }

    @Test
    @DisplayName("超过 10 MB：Service 层防线拦截 400，不落盘（容器层防线需真实 HTTP 验证）")
    void uploadOversizedFileReturns400() throws Exception {
        byte[] huge = new byte[11 * 1024 * 1024];

        mockMvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile(
                                "file", "big.pdf", "application/pdf", huge)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件过大：单个文件不能超过 10 MB"));

        assertStorageDirIsEmpty();
    }

    @Test
    @DisplayName("存储失败（目录被普通文件占用）：500 + 通用提示，响应不泄露磁盘路径")
    void uploadStorageFailureReturns500() throws Exception {
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "占用者");
        LocalFileStorageService brokenStorage = new LocalFileStorageService(blocker.toString());
        MockMvc brokenMvc = MockMvcBuilders
                .standaloneSetup(new DocumentController(
                        new DocumentService(mock(DocumentMapper.class),
                                mock(docflow_backend.mapper.DocumentChunkMapper.class), brokenStorage,
                                new docflow_backend.parser.PdfTextExtractor(), new docflow_backend.parser.DocxTextExtractor(),
                                new docflow_backend.parser.TextContentCleaner(),
                                new docflow_backend.parser.TextChunker())))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        String body = brokenMvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile(
                                "file", "a.pdf", "application/pdf", "x".getBytes())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("文件操作失败，请稍后重试"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("blocker"), "响应不能泄露存储路径细节");
    }

    @Test
    @DisplayName("数据库插入失败：删除刚落盘的文件，再返回统一的 500")
    void uploadDatabaseFailureDeletesStoredFile() throws Exception {
        when(documentMapper.insert(org.mockito.ArgumentMatchers.any(Document.class)))
                .thenThrow(new RuntimeException("database unavailable"));

        mockMvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile(
                                "file", "will-clean-up.pdf", "application/pdf", "x".getBytes())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后重试"));

        assertStorageDirIsEmpty();
    }

    /** 校验失败的请求连存储服务都不该碰到：目录必须空空如也。 */
    private void assertStorageDirIsEmpty() throws IOException {
        try (Stream<Path> entries = Files.list(tempDir)) {
            assertEquals(0, entries.count(), "校验失败时不应有任何文件落盘");
        }
    }
}
