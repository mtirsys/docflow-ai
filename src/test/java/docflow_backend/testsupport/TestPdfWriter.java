package docflow_backend.testsupport;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Day 20 测试专用：用 PDFBox 现场生成各种 PDF 样本文件（仅测试代码可用，不进生产 jar）。
 *
 * 为什么不手写 PDF 字节：PDF 是二进制格式，手工拼字节极易造出"看似损坏实则语法错"的
 * 伪样本。用 PDFBox 生成的样本保证结构合法，测试失败时能确定问题在业务代码而不是样本。
 *
 * 放在 testsupport 包：被 PdfTextExtractorTest（单元层）和 DocumentContentTest（HTTP 层）
 * 共用，造样本的逻辑只写一份。
 */
public final class TestPdfWriter {

    /** 工具类禁止实例化。 */
    private TestPdfWriter() {
    }

    /** 生成一页、写有一行文本（内置 Helvetica 字体，只支持英文）的合法 PDF。 */
    public static void writeTextPdf(Path file, String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            // PDFBox 3.x 中内置 14 种字体改由 Standard14Fonts.FontName 枚举引用
            // （2.x 时代的 PDType1Font.HELVETICA 静态常量写法已删除）。
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream contents = new PDPageContentStream(doc, page)) {
                contents.beginText();
                contents.setFont(font, 12);
                contents.newLineAtOffset(72, 720);
                contents.showText(text);
                contents.endText();
            }
            doc.save(file.toFile());
        }
    }

    /** 生成一页、写有一行中文（嵌入 simhei.ttf 黑体，Unicode 字体）的合法 PDF。 */
    public static void writeChineseTextPdf(Path file, String text) throws IOException {
        Path fontFile = findChineseFont();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            // PDType0Font.load 会把 TrueType 字体子集嵌入 PDF——内置的 Helvetica
            // 只覆盖拉丁字符，中文必须嵌入一个 Unicode 字体才能正常提取。
            PDType0Font font = PDType0Font.load(doc, fontFile.toFile());
            try (PDPageContentStream contents = new PDPageContentStream(doc, page)) {
                contents.beginText();
                contents.setFont(font, 12);
                contents.newLineAtOffset(72, 720);
                contents.showText(text);
                contents.endText();
            }
            doc.save(file.toFile());
        }
    }

    /**
     * 生成一页"空 PDF"：页面存在但没有任何文字内容流，
     * 模拟"没有文字层的 PDF"（扫描图片型 PDF 的极简替身——
     * 真正的扫描件是整页图片，对提取器来说同样"抠不出字"）。
     */
    public static void writeBlankPdf(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(file.toFile());
        }
    }

    /** 生成一个设置了用户口令（打开密码）的加密 PDF，密码错误（或没有）就打不开。 */
    public static void writeEncryptedPdf(Path file, String text) throws IOException {
        writeTextPdf(file, text);
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
            AccessPermission permission = new AccessPermission();
            // 第一个参数是所有者口令，第二个是用户口令（打开密码）。
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner-pass", "user-pass", permission);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(file.toFile());
        }
    }

    /** 生成 pages 页全是空白页的 PDF，用于页数上限测试（加一页几乎不增加体积）。 */
    public static void writeManyBlankPagesPdf(Path file, int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(file.toFile());
        }
    }

    /** 写入一堆"根本不是 PDF"的字节，模拟内容损坏的文件。 */
    public static void writeCorruptedPdf(Path file) throws IOException {
        Files.writeString(file, "this is definitely not a pdf file at all");
    }

    /** 在常见 Windows 字体目录里找可嵌入的中文 TTF 字体；找不到返回 null（调用方跳过用例）。 */
    public static Path findChineseFont() {
        String[] candidates = {
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/Deng.ttf",
                "C:/Windows/Fonts/simfang.ttf"
        };
        for (String candidate : candidates) {
            File f = new File(candidate);
            if (f.isFile()) {
                return f.toPath();
            }
        }
        return null;
    }
}
