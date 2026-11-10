package docflow_backend.parser;

import docflow_backend.exception.DocumentParseException;
import docflow_backend.exception.FileStorageException;
import docflow_backend.testsupport.TestPdfWriter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day 20：PdfTextExtractor 的单元测试（最内层）——不起 Spring、不起 MockMvc、不碰数据库，
 * 直接 new 出被测对象，用 TestPdfWriter 现场生成真实 PDF 样本。
 *
 * 三层测试的分工：
 * - 本类（单元层）：验证"提取器对各种 PDF 输入的翻译"——成功、损坏、加密、空白、超限；
 * - DocumentContentTest（HTTP 层）：验证整条链路（Controller→Service→提取器→DTO→JSON）；
 * - requests.http（手工层）：真机验证，见该文件 Day 20 段。
 */
class PdfTextExtractorTest {

    @TempDir
    Path tempDir;

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    @DisplayName("正常 PDF：提取出的文本包含写入的原文")
    void extractsTextFromValidPdf() throws IOException {
        Path pdf = tempDir.resolve("sample.pdf");
        TestPdfWriter.writeTextPdf(pdf, "Hello DocFlow Day 20");

        String text = extractor.extract(pdf);

        // PDFTextStripper 会在页尾补换行/换页符，所以断言用 contains 而不是逐字相等。
        assertTrue(text.contains("Hello DocFlow Day 20"),
                "提取结果应包含原文，实际是：" + text);
    }

    @Test
    @DisplayName("中文 PDF：嵌入 simhei 字体后中文可完整提取")
    void extractsChineseTextFromPdf() throws IOException {
        Path fontFile = TestPdfWriter.findChineseFont();
        // 环境里没有可嵌入的中文字体时跳过本用例（例如非 Windows 环境），
        // 跳过≠失败：JUnit 会把用例标记为 skipped 而不是 red。
        Assumptions.assumeTrue(fontFile != null, "本机没有可用的中文 TTF 字体，跳过");

        Path pdf = tempDir.resolve("chinese.pdf");
        String sentence = "文档系统第二十天：PDF 文本提取功能。";
        TestPdfWriter.writeChineseTextPdf(pdf, sentence);

        String text = extractor.extract(pdf);

        assertTrue(text.contains("文档系统第二十天：PDF 文本提取功能。"),
                "中文应逐字提取（出现 ???? 或锟斤拷 即编码错误），实际是：" + text);
    }

    @Test
    @DisplayName("损坏的 PDF：抛 DocumentParseException，提示无法解析")
    void corruptedPdfThrowsParseException() throws IOException {
        Path pdf = tempDir.resolve("corrupted.pdf");
        TestPdfWriter.writeCorruptedPdf(pdf);

        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> extractor.extract(pdf));
        assertTrue(e.getMessage().contains("损坏"));
    }

    @Test
    @DisplayName("加密 PDF（设了打开密码）：抛 DocumentParseException，提示已加密")
    void encryptedPdfThrowsParseException() throws IOException {
        Path pdf = tempDir.resolve("encrypted.pdf");
        TestPdfWriter.writeEncryptedPdf(pdf, "top secret");

        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> extractor.extract(pdf));
        assertTrue(e.getMessage().contains("加密"),
                "应识别为加密而不是误报为损坏，实际提示：" + e.getMessage());
    }

    @Test
    @DisplayName("没有文字层的 PDF：抛 DocumentParseException，提示可能是扫描件")
    void blankPdfThrowsParseException() throws IOException {
        Path pdf = tempDir.resolve("blank.pdf");
        TestPdfWriter.writeBlankPdf(pdf);

        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> extractor.extract(pdf));
        assertTrue(e.getMessage().contains("没有可提取的文本"),
                "空白页/扫描件应被 isBlank 识别，实际提示：" + e.getMessage());
    }

    @Test
    @DisplayName("超过页数上限的 PDF：抛 DocumentParseException（301 页 > 上限 300）")
    void tooManyPagesThrowsParseException() throws IOException {
        Path pdf = tempDir.resolve("many-pages.pdf");
        TestPdfWriter.writeManyBlankPagesPdf(pdf, 301);

        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> extractor.extract(pdf));
        assertTrue(e.getMessage().contains("页数"),
                "应命中页数防线，实际提示：" + e.getMessage());
    }

    @Test
    @DisplayName("超过大小上限的文件：抛 FileStorageException，且根本不进入 PDF 解析")
    void oversizedFileThrowsStorageException() throws IOException {
        Path pdf = tempDir.resolve("oversized.pdf");
        // 写一个 10MB + 1 字节的文件，只需超线一个字节即可验证边界判断。
        Files.write(pdf, new byte[10 * 1024 * 1024 + 1]);

        // 大小防线读 Files.size（不看内容），所以文件内容不必是合法 PDF。
        FileStorageException e = assertThrows(FileStorageException.class,
                () -> extractor.extract(pdf));
        assertTrue(e.getMessage().contains("过大"));
    }
}
