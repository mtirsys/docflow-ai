package docflow_backend.controller;

import com.jayway.jsonpath.JsonPath;
import docflow_backend.exception.GlobalExceptionHandler;
import docflow_backend.mapper.DocumentMapper;
import docflow_backend.model.Document;
import docflow_backend.service.DocumentService;
import docflow_backend.storage.LocalFileStorageService;
import docflow_backend.testsupport.TestDocxWriter;
import docflow_backend.testsupport.TestPdfWriter;
import org.junit.jupiter.api.Assumptions;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/documents/{id}/content 的自动化测试（MockMvc standalone，不起服务、不连数据库）。
 * Day 19 覆盖 Markdown 读取；Day 20 扩展覆盖 PDF 提取（正常/中文/丢失/损坏/加密/无文字层）；
 * Day 21 扩展覆盖 DOCX 提取（正常/中文/表格/空/损坏/丢失）。
 *
 * 装配取舍和 DocumentDownloadTest 同源：
 * - mock 的 DocumentMapper 是"边界外的邻居"——测试需要它按剧本回答 findById，
 *   好制造"记录不存在 / 记录在但文件丢 / 类型不支持"几种场景；
 * - 真的 LocalFileStorageService + 真的 PdfTextExtractor + @TempDir 是"今天的主角"——
 *   被测核心就是"真读盘、真解析 PDF"：PDF 样本由 TestPdfWriter 现场生成。
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
        DocumentService documentService = new DocumentService(
                documentMapper, mock(docflow_backend.mapper.DocumentChunkMapper.class), storageService,
                new docflow_backend.parser.PdfTextExtractor(),
                new docflow_backend.parser.DocxTextExtractor(), new docflow_backend.parser.TextContentCleaner(),
                new docflow_backend.parser.TextChunker());

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
    @DisplayName("file_type 是 TXT：400 + 明确提示支持的类型")
    void readUnsupportedTypeReturns400() throws Exception {
        // Day 20 放行了 PDF，Day 21 放行了 DOCX，
        // "类型不支持"的守门测试改由 TXT 扮演被拒类型（白名单里根本没有它）。
        String txtKey = "22222222-3333-4444-5555-666666666666.txt";
        seedDocument(17L, txtKey, "notes.txt", "TXT", "plain text bytes".getBytes(StandardCharsets.UTF_8));

        String body = bodyOf(mockMvc.perform(get("/api/documents/17/content"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("不支持内容读取")))
                .andReturn());

        assertFalse(body.contains(txtKey), "类型被拒时不应暴露 storageKey");
    }

    @Test
    @DisplayName("正常 PDF：200 + 提取出的文本进 content，storageKey 不外泄")
    void readExistingPdfReturnsExtractedContent() throws Exception {
        String pdfKey = "33333333-4444-5555-6666-777777777777.pdf";
        Path pdfFile = tempDir.resolve(pdfKey);
        TestPdfWriter.writeTextPdf(pdfFile, "Hello DocFlow Day 20");
        seedDocument(21L, pdfKey, "report.pdf", "PDF", null);

        String body = bodyOf(mockMvc.perform(get("/api/documents/21/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.originalFilename").value("report.pdf"))
                .andReturn());

        String content = JsonPath.read(body, "$.content");
        assertTrue(content.contains("Hello DocFlow Day 20"),
                "整条链路提取的文本应包含原文，实际是：" + content);
        assertEquals(content.length(), (int) JsonPath.read(body, "$.characterCount"));
        assertFalse(body.contains(pdfKey), "storageKey 是内部细节，不允许出现在响应里");
    }

    @Test
    @DisplayName("中文 PDF：UTF-8 全链路无乱码")
    void readChinesePdfWithoutMojibake() throws Exception {
        Assumptions.assumeTrue(TestPdfWriter.findChineseFont() != null, "本机无中文 TTF 字体，跳过");

        String pdfKey = "44444444-5555-6666-7777-888888888888.pdf";
        Path pdfFile = tempDir.resolve(pdfKey);
        TestPdfWriter.writeChineseTextPdf(pdfFile, "季度报告：PDF 文本提取功能上线。");
        seedDocument(22L, pdfKey, "季度报告.pdf", "PDF", null);

        String body = bodyOf(mockMvc.perform(get("/api/documents/22/content"))
                .andExpect(status().isOk())
                .andReturn());

        String content = JsonPath.read(body, "$.content");
        assertTrue(content.contains("季度报告：PDF 文本提取功能上线。"),
                "中文应逐字还原，实际是：" + content);
    }

    @Test
    @DisplayName("PDF 类型但文件被删：404 + 如实说'文件已丢失'（与 MD 同一条翻译）")
    void readPdfWhenFileLostReturns404() throws Exception {
        String pdfKey = "55555555-6666-7777-8888-999999999999.pdf";
        // 只造数据库记录（content = null，不落盘），模拟文件被人手动删除。
        seedDocument(23L, pdfKey, "report.pdf", "PDF", null);

        String body = bodyOf(mockMvc.perform(get("/api/documents/23/content"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("文档存在，但文件已丢失，请联系管理员"))
                .andReturn());

        assertFalse(body.contains(tempDir.toString()), "响应不能泄露存储目录的真实路径");
    }

    @Test
    @DisplayName("损坏的 PDF：422 + 提示无法解析（专属翻译，不再伪装成成功或笼统 500）")
    void readCorruptedPdfReturns422() throws Exception {
        String pdfKey = "66666666-7777-8888-9999-000000000000.pdf";
        TestPdfWriter.writeCorruptedPdf(tempDir.resolve(pdfKey));
        seedDocument(24L, pdfKey, "broken.pdf", "PDF", null);

        mockMvc.perform(get("/api/documents/24/content"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("PDF 文件损坏或格式无法解析"));
    }

    @Test
    @DisplayName("加密的 PDF：422 + 提示已加密（不被误报成损坏）")
    void readEncryptedPdfReturns422() throws Exception {
        String pdfKey = "77777777-8888-9999-0000-111111111111.pdf";
        TestPdfWriter.writeEncryptedPdf(tempDir.resolve(pdfKey), "top secret");
        seedDocument(25L, pdfKey, "secret.pdf", "PDF", null);

        mockMvc.perform(get("/api/documents/25/content"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("PDF 已加密，无法提取文本"));
    }

    @Test
    @DisplayName("没有文字层的 PDF（扫描件替身）：422 + 提示不支持 OCR")
    void readBlankPdfReturns422() throws Exception {
        String pdfKey = "88888888-9999-0000-1111-222222222222.pdf";
        TestPdfWriter.writeBlankPdf(tempDir.resolve(pdfKey));
        seedDocument(26L, pdfKey, "scanned.pdf", "PDF", null);

        mockMvc.perform(get("/api/documents/26/content"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value(
                        "PDF 中没有可提取的文本（可能是扫描图片型 PDF，暂不支持 OCR）"));
    }

    @Test
    @DisplayName("正常 DOCX：200 + POI 提取的文本进 content，storageKey 不外泄")
    void readExistingDocxReturnsExtractedContent() throws Exception {
        String docxKey = "12345678-4321-4321-4321-210987654321.docx";
        TestDocxWriter.writeTextDocx(tempDir.resolve(docxKey), "Hello DocFlow Day 21");
        seedDocument(31L, docxKey, "report.docx", "DOCX", null);

        String body = bodyOf(mockMvc.perform(get("/api/documents/31/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(31))
                .andExpect(jsonPath("$.originalFilename").value("report.docx"))
                .andReturn());

        String content = JsonPath.read(body, "$.content");
        assertTrue(content.contains("Hello DocFlow Day 21"),
                "整条链路提取的文本应包含原文，实际是：" + content);
        assertEquals(content.length(), (int) JsonPath.read(body, "$.characterCount"));
        assertFalse(body.contains(docxKey), "storageKey 是内部细节，不允许出现在响应里");
    }

    @Test
    @DisplayName("中文 DOCX：UTF-8 全链路无乱码（DOCX 原生 Unicode，无需字体假设）")
    void readChineseDocxWithoutMojibake() throws Exception {
        String docxKey = "23456789-4321-4321-4321-210987654321.docx";
        TestDocxWriter.writeTextDocx(tempDir.resolve(docxKey), "季度报告：Word 文本提取功能上线。");
        seedDocument(32L, docxKey, "季度报告.docx", "DOCX", null);

        String body = bodyOf(mockMvc.perform(get("/api/documents/32/content"))
                .andExpect(status().isOk())
                .andReturn());

        String content = JsonPath.read(body, "$.content");
        assertTrue(content.contains("季度报告：Word 文本提取功能上线。"),
                "中文应逐字还原，实际是：" + content);
    }

    @Test
    @DisplayName("含表格的 DOCX：200 + 表格单元格文字同样进入 content")
    void readTableDocxReturnsCellText() throws Exception {
        String docxKey = "34567890-4321-4321-4321-210987654321.docx";
        TestDocxWriter.writeTableDocx(tempDir.resolve(docxKey),
                new String[]{"姓名", "成绩"},
                new String[]{"张三", "95"});
        seedDocument(33L, docxKey, "成绩表.docx", "DOCX", null);

        String body = bodyOf(mockMvc.perform(get("/api/documents/33/content"))
                .andExpect(status().isOk())
                .andReturn());

        String content = JsonPath.read(body, "$.content");
        assertTrue(content.contains("张三") && content.contains("95"),
                "表格单元格文字应进入整条链路的提取结果，实际是：" + content);
    }

    @Test
    @DisplayName("空 DOCX：422 + 如实说没有可提取的文本（不伪装成成功）")
    void readEmptyDocxReturns422() throws Exception {
        String docxKey = "45678901-4321-4321-4321-210987654321.docx";
        TestDocxWriter.writeEmptyDocx(tempDir.resolve(docxKey));
        seedDocument(34L, docxKey, "blank.docx", "DOCX", null);

        mockMvc.perform(get("/api/documents/34/content"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value(
                        "DOCX 中没有可提取的文本（可能是空文档或纯图片文档）"));
    }

    @Test
    @DisplayName("损坏的 DOCX（txt 改名）：422 + 提示无法解析（不伪装成功，也不误报 500）")
    void readCorruptedDocxReturns422() throws Exception {
        String docxKey = "56789012-4321-4321-4321-210987654321.docx";
        TestDocxWriter.writeCorruptedDocx(tempDir.resolve(docxKey));
        seedDocument(35L, docxKey, "broken.docx", "DOCX", null);

        mockMvc.perform(get("/api/documents/35/content"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("DOCX 文件损坏或格式无法解析"));
    }

    @Test
    @DisplayName("DOCX 类型但文件被删：404 + 如实说'文件已丢失'（三种类型同一条翻译）")
    void readDocxWhenFileLostReturns404() throws Exception {
        String docxKey = "67890123-4321-4321-4321-210987654321.docx";
        // 只造数据库记录（content = null，不落盘），模拟文件被人手动删除。
        seedDocument(36L, docxKey, "report.docx", "DOCX", null);

        String body = bodyOf(mockMvc.perform(get("/api/documents/36/content"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("文档存在，但文件已丢失，请联系管理员"))
                .andReturn());

        assertFalse(body.contains(tempDir.toString()), "响应不能泄露存储目录的真实路径");
    }
}
