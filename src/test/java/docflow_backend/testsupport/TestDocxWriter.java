package docflow_backend.testsupport;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Day 21 测试专用：用 Apache POI 现场生成各种 DOCX 样本文件（仅测试代码可用，不进生产 jar）。
 *
 * 为什么不手写 DOCX 字节：DOCX 是 ZIP + XML 的复合格式，手工拼字节极易造出
 * "看似损坏实则语法错"的伪样本。用 POI 生成的样本保证结构合法，
 * 测试失败时能确定问题在业务代码而不是样本——与 TestPdfWriter 同一条纪律。
 *
 * 放在 testsupport 包：被 DocxTextExtractorTest（单元层）和
 * DocumentContentTest（HTTP 层）共用，造样本的逻辑只写一份。
 *
 * 一个与 PDF 的有趣差异：PDF 提取中文必须嵌入 TTF 字体（文字被画成字形，
 * 提取靠字体映射），而 DOCX 的正文本来就直接以 Unicode 明文存进 XML——
 * 所以这里生成中文样本不需要任何字体准备，也不需要 Assumptions 跳过。
 */
public final class TestDocxWriter {

    /** 工具类禁止实例化。 */
    private TestDocxWriter() {
    }

    /** 生成只含一个段落文本的合法 DOCX。 */
    public static void writeTextDocx(Path file, String text) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph paragraph = doc.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(text);
            save(doc, file);
        }
    }

    /**
     * 生成只含一个表格的合法 DOCX，cells 按行传入：
     * writeTableDocx(file, new String[]{"姓名","成绩"}, new String[]{"张三","95"})
     * 生成 2 行 2 列、每格一串文字的表格。
     */
    public static void writeTableDocx(Path file, String[]... cells) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(cells.length, cells[0].length);
            for (int r = 0; r < cells.length; r++) {
                for (int c = 0; c < cells[r].length; c++) {
                    table.getRow(r).getCell(c).setText(cells[r][c]);
                }
            }
            save(doc, file);
        }
    }

    /**
     * 生成"空 DOCX"：结构完全合法的 Office 文件，但没有任何段落和表格——
     * 提取器拆得开它，只是抠不出一个字（正好喂给 isBlank 诚实检查）。
     */
    public static void writeEmptyDocx(Path file) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            save(doc, file);
        }
    }

    /** 写入一堆"根本不是 Office 文件"的字节，模拟 txt 改名 .docx 或内容损坏。 */
    public static void writeCorruptedDocx(Path file) throws IOException {
        Files.writeString(file, "this is definitely not a docx file at all");
    }

    /** XWPFDocument.write 只收输出流；包一个私有 save 统一"写完就关流"。 */
    private static void save(XWPFDocument doc, Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            doc.write(out);
        }
    }
}
