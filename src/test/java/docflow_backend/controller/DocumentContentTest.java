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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 19：GET /api/documents/{id}/content 的自动化测试（MockMvc standalone，不起服务、不连数据库）。
 *
 * 装配取舍和 DocumentDownloadTest 同源：
 * - mock 的 DocumentMapper 是"边界外的邻居"——测试需要它按剧本回答 findById，
 *   好制造"记录不存在 / 记录在但文件丢 / 类型不是 MD"几种场景；
 * - 真的 LocalFileStorageService + @TempDir 是"今天的主角"——
 *   被测核心就是"真读盘"：文件真实存在于磁盘（或不存在），UTF-8 中文真实读回。
 *
 * 中文断言的坑：MockHttpServletResponse 默认按 ISO-8859-1 解码响应体会得到乱码，
 * 所以涉及正文的断言统一用 getContentAsString(StandardCharsets.UTF_8) 拿到完整
 * JSON 字符串后用 JsonPath 提取，编码全程显式可控。
 */
class DocumentContentTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private DocumentMapper documentMapper;

    /** 造一个像 Day 16 真实上传会生成的 storageKey（UUID + 白名单扩展名）。 */
    private static final String MD_KEY = "11111111-2222-3333-4444-555555555555.md";

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
     * 与 DocumentDownloadTest.seedDocument 的区别：多一个 fileType 参数——
     * 今天要测"类型不是 MD 被拒绝"，所以类型必须由用例自己指定。
     */
    private void seedDocument(long id, String storageKey, String originalFilename,
                              String fileType, byte[] content) throws Exception {
        if (content != null) {
            Files.write(tempDir.resolve(storageKey), content);
        }
        Document document = new Document();
        document.setId(id);
        document.setTitle(originalFilename);
        document.setFileType(fileType);
        document.setStorageKey(storageKey);
        document.setOriginalFilename(originalFilename);
        document.setFileSize(content == null ? 0L : (long) content.length);
        document.setStatus("UPLOADED");
        when(documentMapper.findById(id)).thenReturn(document);
    }

    /** 显式按 UTF-8 拿响应体全文（规避 MockMvc 默认 ISO-8859-1 的乱码坑）。 */
    private String bodyOf(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("正常读取 Markdown：200 + 四个字段齐全 + content 与磁盘逐字一致")
    void readExistingMarkdownReturnsContent() throws Exception {
        String markdown = "# Java 学习笔记\n\nDay 19: content endpoint works.";
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        seedDocument(15L, MD_KEY, "notes.md", "MD", bytes);

        String body = bodyOf(mockMvc.perform(get("/api/documents/15/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(15))
                .andExpect(jsonPath("$.originalFilename").value("notes.md"))
                .andExpect(jsonPath("$.characterCount").value(markdown.length()))
                .andReturn());

        String content = JsonPath.read(body, "$.content");
        assertEquals(markdown, content, "读回的正文必须与写入磁盘的原文逐字一致");
        // 再和磁盘现场对一次：响应内容 = Files.readString(UTF-8) 的结果
        assertEquals(Files.readString(tempDir.resolve(MD_KEY), StandardCharsets.UTF_8), content);
        assertFalse(body.contains(MD_KEY), "storageKey 是内部细节，不允许出现在响应里");
    }

    @Test
    @DisplayName("中文 Markdown 不乱码：显式 UTF-8 读取，中文逐字还原")
    void readChineseMarkdownWithoutMojibake() throws Exception {
        String markdown = "# 季度报告\n\n本月完成了文档内容读取功能，中文不应乱码。";
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        seedDocument(16L, MD_KEY, "季度报告.md", "MD", bytes);

        String body = bodyOf(mockMvc.perform(get("/api/documents/16/content"))
                .andExpect(status().isOk())
                .andReturn());

        String content = JsonPath.read(body, "$.content");
        assertEquals(markdown, content, "中文必须逐字一致；出现 ???? 或锟斤拷 即编码错误");
        assertEquals(markdown.length(), (int) JsonPath.read(body, "$.characterCount"));
    }

    @Test
    @DisplayName("不存在的文档 ID：404 + 统一错误体（复用 Day 10 机制）")
    void readUnknownIdReturns404() throws Exception {
        when(documentMapper.findById(999L)).thenReturn(null);

        mockMvc.perform(get("/api/documents/999/content"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("文档不存在：id=999"));
    }

    @Test
    @DisplayName("数据库有记录、磁盘无文件：404 + 如实说'文件已丢失'，且不泄露磁盘路径")
    void readWhenFileLostReturns404WithoutLeakingPaths() throws Exception {
        // 只造数据库记录（content = null，不落盘），模拟文件被人手动删除。
        seedDocument(15L, MD_KEY, "notes.md", "MD", null);

        String body = bodyOf(mockMvc.perform(get("/api/documents/15/content"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("文档存在，但文件已丢失，请联系管理员"))
                .andReturn());

        assertFalse(body.contains(tempDir.toString()), "响应不能泄露存储目录的真实路径");
    }

    @Test
    @DisplayName("file_type 不是 Markdown（PDF）：400 + 明确提示仅支持 Markdown")
    void readNonMarkdownTypeReturns400() throws Exception {
        // 记录是 PDF：文档存在、文件也在盘上，但内容读取只放行 MD。
        String pdfKey = "22222222-3333-4444-5555-666666666666.pdf";
        seedDocument(17L, pdfKey, "report.pdf", "PDF", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));

        String body = bodyOf(mockMvc.perform(get("/api/documents/17/content"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("仅支持 Markdown")))
                .andReturn());

        assertFalse(body.contains(pdfKey), "类型被拒时不应暴露 storageKey");
    }
}
