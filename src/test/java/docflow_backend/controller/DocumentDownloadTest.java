package docflow_backend.controller;

import docflow_backend.exception.GlobalExceptionHandler;
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

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 17：GET /api/documents/{id}/download 的自动化测试（MockMvc standalone，不起服务、不连数据库）。
 *
 * 装配取舍和 DocumentUploadTest 同源：
 * - mock 的 DocumentMapper 是"边界外的邻居"——测试需要它按剧本回答 findById，
 *   好制造"记录存在 / 记录不存在 / 记录在但文件丢"三种场景；
 * - 真的 LocalFileStorageService + @TempDir 是"今天的主角"——
 *   被测核心就是"真读盘"：文件真的在磁盘上存在或不存在，字节真的从盘上读回。
 */
class DocumentDownloadTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private DocumentMapper documentMapper;

    /** 造一个像 Day 16 真实上传会生成的 storageKey（UUID + 白名单扩展名）。 */
    private static final String PDF_KEY = "11111111-2222-3333-4444-555555555555.pdf";

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        LocalFileStorageService storageService = new LocalFileStorageService(tempDir.toString());
        DocumentService documentService = new DocumentService(documentMapper, storageService);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentController(documentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 场景搭台：往临时存储目录写一个"已上传"的文件（content 为 null 则只造记录不落盘），
     * 并让 mock 的 Mapper 对 findById(id) 返回对应的 Document。
     */
    private void seedDocument(long id, String storageKey, String originalFilename, byte[] content)
            throws Exception {
        if (content != null) {
            Files.write(tempDir.resolve(storageKey), content);
        }
        Document document = new Document();
        document.setId(id);
        document.setTitle(originalFilename);
        document.setFileType("PDF");
        document.setStorageKey(storageKey);
        document.setOriginalFilename(originalFilename);
        document.setFileSize(content == null ? 0L : (long) content.length);
        document.setStatus("UPLOADED");
        when(documentMapper.findById(id)).thenReturn(document);
    }

    @Test
    @DisplayName("正常下载：200 + application/pdf + attachment 含原始文件名 + 字节与磁盘一致 + 自动 Content-Length")
    void downloadExistingPdfReturnsFileBytesAndName() throws Exception {
        byte[] content = "%PDF-1.4 real report content".getBytes(StandardCharsets.UTF_8);
        seedDocument(15L, PDF_KEY, "report.pdf", content);

        byte[] body = mockMvc.perform(get("/api/documents/15/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("report.pdf")))
                .andExpect(header().string("Content-Length", String.valueOf(content.length)))
                .andReturn().getResponse().getContentAsByteArray();

        assertArrayEquals(content, body, "下载的字节必须与磁盘上的文件完全一致");
    }

    @Test
    @DisplayName("不存在的文档 ID：404 + 统一错误体（复用 Day 10 机制）")
    void downloadUnknownIdReturns404() throws Exception {
        when(documentMapper.findById(999L)).thenReturn(null);

        mockMvc.perform(get("/api/documents/999/download"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("文档不存在：id=999"));
    }

    @Test
    @DisplayName("数据库有记录、磁盘无文件：404 + 如实说'文件已丢失'，且不泄露任何磁盘路径")
    void downloadWhenFileLostReturns404WithoutLeakingPaths() throws Exception {
        // 只造数据库记录（content = null，不落盘），模拟文件被人手动删除。
        seedDocument(15L, PDF_KEY, "report.pdf", null);

        String body = mockMvc.perform(get("/api/documents/15/download"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("文档存在，但文件已丢失，请联系管理员"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(tempDir.toString()), "响应不能泄露存储目录的真实路径");
        assertFalse(body.contains(PDF_KEY), "响应不需要暴露 storageKey 细节");
    }

    @Test
    @DisplayName("中文文件名：Content-Disposition 按 RFC 5987 输出 UTF-8 编码形式，浏览器可正确还原")
    void downloadChineseFilenameEncodesUtf8() throws Exception {
        byte[] content = "# 季度报告".getBytes(StandardCharsets.UTF_8);
        String mdKey = "22222222-3333-4444-5555-666666666666.md";
        seedDocument(16L, mdKey, "季度报告.md", content);

        String disposition = mockMvc.perform(get("/api/documents/16/download"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Content-Disposition");

        assertTrue(disposition.contains("attachment"), "必须是 attachment 触发浏览器保存");
        // 关键断言：非 ASCII 文件名必须走 RFC 5987 的 filename*=UTF-8''… 百分号编码形式，
        // 而不是把中文裸放进响应头（真实 Tomcat 下裸中文会变乱码）。
        assertTrue(disposition.contains("filename*="),
                "非 ASCII 文件名应使用 filename* 形式，实际是：" + disposition);
        assertTrue(disposition.contains("UTF-8"),
                "应声明 UTF-8 字符集，实际是：" + disposition);
        assertTrue(disposition.contains("%E5%AD%A3"),
                "中文应被百分号编码（季 的 UTF-8 首字节是 %E5%AD%A3），实际是：" + disposition);
        // 解码后还原验证：编码再花哨，浏览器解出来必须是原始文件名。
        String decoded = java.net.URLDecoder.decode(disposition, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("季度报告.md"),
                "解码后的 Content-Disposition 应包含原始中文文件名，实际是：" + disposition);
    }
}
