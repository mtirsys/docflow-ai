package docflow_backend.parser;

import docflow_backend.exception.DocumentParseException;
import docflow_backend.exception.FileStorageException;
import docflow_backend.testsupport.TestDocxWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day 21：DocxTextExtractor 的单元测试（最内层）——不起 Spring、不起 MockMvc、不碰数据库，
 * 直接 new 出被测对象，用 TestDocxWriter 现场生成真实 DOCX 样本。
 *
 * 三层测试的分工（与 Day 20 同款）：
 * - 本类（单元层）：验证"提取器对各种 DOCX 输入的翻译"——成功、中文、表格、空、损坏、超限；
 * - DocumentContentTest（HTTP 层）：验证整条链路（Controller→Service→提取器→DTO→JSON）；
 * - requests.http（手工层）：真机验证，见该文件 Day 21 段。
 */
class DocxTextExtractorTest {

    @TempDir
    Path tempDir;

    private final DocxTextExtractor extractor = new DocxTextExtractor();

    @Test
    @DisplayName("正常 DOCX：提取出的文本包含写入的原文")
    void extractsTextFromValidDocx() throws IOException {
        Path docx = tempDir.resolve("sample.docx");
        TestDocxWriter.writeTextDocx(docx, "Hello DocFlow Day 21");

        String text = extractor.extract(docx);

        // getText() 会在段落间补换行等空白，断言用 contains 而不是逐字相等（与 PDF 同理）。
        assertTrue(text.contains("Hello DocFlow Day 21"),
                "提取结果应包含原文，实际是：" + text);
    }

    @Test
    @DisplayName("中文 DOCX：正文以 Unicode 明文存于 XML，中文应逐字提取（无需嵌入字体）")
    void extractsChineseTextFromDocx() throws IOException {
        Path docx = tempDir.resolve("chinese.docx");
        TestDocxWriter.writeTextDocx(docx, "文档系统第二十一天：Word 文本提取功能。");

        String text = extractor.extract(docx);

        assertTrue(text.contains("文档系统第二十一天：Word 文本提取功能。"),
                "中文应逐字提取（出现 ???? 或锟斤拷 即编码错误），实际是：" + text);
    }

    @Test
    @DisplayName("表格 DOCX：XWPFWordExtractor 自动遍历表格，单元格文字进入结果")
    void extractsTableTextFromDocx() throws IOException {
        Path docx = tempDir.resolve("table.docx");
        TestDocxWriter.writeTableDocx(docx,
                new String[]{"姓名", "成绩"},
                new String[]{"张三", "95"});

        String text = extractor.extract(docx);

        // 四个单元格的文字都必须出现——证明抄写员真的走进了表格，而不是只扫段落。
        assertTrue(text.contains("姓名") && text.contains("成绩")
                        && text.contains("张三") && text.contains("95"),
                "表格的四个单元格都应被提取，实际是：" + text);
    }

    @Test
    @DisplayName("空 DOCX：抛 DocumentParseException，提示没有可提取的文本")
    void emptyDocxThrowsParseException() throws IOException {
        Path docx = tempDir.resolve("empty.docx");
        TestDocxWriter.writeEmptyDocx(docx);

        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> extractor.extract(docx));
        assertTrue(e.getMessage().contains("没有可提取的文本"),
                "空文档应命中 isBlank 诚实检查，实际提示：" + e.getMessage());
    }

    @Test
    @DisplayName("损坏的 DOCX（txt 改名）：抛 DocumentParseException，提示无法解析")
    void corruptedDocxThrowsParseException() throws IOException {
        Path docx = tempDir.resolve("corrupted.docx");
        TestDocxWriter.writeCorruptedDocx(docx);

        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> extractor.extract(docx));
        assertTrue(e.getMessage().contains("损坏"));
    }

    @Test
    @DisplayName("超过大小上限的文件：抛 FileStorageException，且根本不进入 POI 解析")
    void oversizedFileThrowsStorageException() throws IOException {
        Path docx = tempDir.resolve("oversized.docx");
        // 写一个 10MB + 1 字节的文件，只需超线一个字节即可验证边界判断。
        Files.write(docx, new byte[10 * 1024 * 1024 + 1]);

        // 大小防线读 Files.size（不看内容），所以文件内容不必是合法 DOCX。
        FileStorageException e = assertThrows(FileStorageException.class,
                () -> extractor.extract(docx));
        assertTrue(e.getMessage().contains("过大"));
    }
}
