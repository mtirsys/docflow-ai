package docflow_backend.parser;

import docflow_backend.exception.DocumentParseException;
import docflow_backend.exception.FileStorageException;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Day 21：DOCX 文本提取器——整个项目里唯一知道 Apache POI 存在的类。
 *
 * 在架构里的位置（与 PdfTextExtractor 完全同构）：
 * - LocalFileStorageService 负责"文件在哪、能不能读"（安全定位，交来验过货的 Path）；
 * - 这里负责"DOCX 里有什么字"：拆开 ZIP 外壳、解析内部 XML、按阅读顺序抠出文字；
 * - DocumentService 负责编排：MD 走 readText，PDF 走 PdfTextExtractor，DOCX 走这里。
 *
 * DOCX 的本质（理解三件套为什么这么连）：
 * .docx 是一个 ZIP 压缩包（Office Open XML 标准），里面装着十几个 XML 文件，
 * 其中 word/document.xml 存正文。所以解析是一条流水线：
 * InputStream（字节流）→ XWPFDocument（拆 ZIP + 解析 XML，拼成内存里的文档模型）
 * → XWPFWordExtractor（抄写员：按阅读顺序遍历段落和表格）→ getText()（纯文本）。
 *
 * 与 PdfTextExtractor 的一处口径差异（两家库的脾气不同）：
 * PDFBox 遇到坏文件多半抛 IOException；POI 遇到"根本不是 Office 文件"
 * （如 txt 改名成 .docx）多半抛非受检的 RuntimeException（如 NotOfficeXmlFileException）。
 * 所以这里的 catch 口径放宽到 IOException | RuntimeException，出口仍是
 * DocumentParseException，由全局处理器统一翻译成 422。
 */
@Component
public class DocxTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocxTextExtractor.class);

    /**
     * 与上传白名单、readText、PDF 提取器的 10 MB 口径一致。
     * 上传时已限过一次，这里是第二道防线：不信任数据库里的历史数据。
     */
    private static final long MAX_DOCX_BYTES = 10L * 1024 * 1024;

    /**
     * 把一份 DOCX 解析成纯文本。
     *
     * 防线结构与 PdfTextExtractor 同款：
     * 1. 先看文件大小（只读元数据，最便宜）——超限直接拒绝，连打开都不打开；
     * 2. POI 三件套真正拆包提取。
     *
     * try-with-resources 管三个资源，关闭顺序与声明顺序相反：
     * 先关抄写员（XWPFWordExtractor），再关文档模型（XWPFDocument），
     * 最后关字节水管（InputStream）——就像离场时先送走客人、再收桌子、最后锁门。
     *
     * 空文档如实报错（与 PDF 空白扫描件同口径）：提取结果 isBlank 时抛
     * DocumentParseException，不返回"成功但内容为空"的伪装响应。
     */
    public String extract(Path docxFile) {
        // 防线一：文件大小。先于一切解析动作——超限的文件连打开都不打开。
        try {
            long size = Files.size(docxFile);
            if (size > MAX_DOCX_BYTES) {
                log.error("DOCX 超出解析大小上限，已拒绝：{}（{} 字节）", docxFile, size);
                throw new FileStorageException("DOCX 文件过大，拒绝解析");
            }
        } catch (IOException e) {
            log.error("解析前检查 DOCX 文件大小失败：{}", docxFile, e);
            throw new FileStorageException("DOCX 文件读取失败", e);
        }

        // 三节流水线全部交给 try-with-resources 看 管：
        // in（字节水管）→ document（拆 ZIP + 解 XML 拼出的文档模型）→ extractor（抄写员）。
        // XWPFDocument 和 XWPFWordExtractor 内部都持有解析缓冲，
        // 靠垃圾回收"想起来再回收"不可靠，必须显式关闭。
        try (InputStream in = Files.newInputStream(docxFile);
             XWPFDocument document = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {

            // getText()：让抄写员按阅读顺序走遍段落和表格，交付纯文本。
            // 只交付"字"，不交付"妆"——加粗、字号、颜色一概不留。
            String text = extractor.getText();

            // 诚实检查：空文档（或纯图片文档）没有可交付的文字。
            // 与 PDF 的空白扫描件同一口径：如实报 422，不伪装成功。
            if (text == null || text.isBlank()) {
                throw new DocumentParseException(
                        "DOCX 中没有可提取的文本（可能是空文档或纯图片文档）");
            }
            return text;

        } catch (DocumentParseException e) {
            // 自己抛的"诚实检查"异常直接放行，否则会被下面的宽口径分支
            // 误包成"文件损坏"——和 DocumentService.delete 里放行
            // DocumentNotFoundException 的手法同款。
            throw e;
        } catch (IOException | RuntimeException e) {
            // 走到这里说明文件打不开/解不动：内容损坏、被截断、
            // 或根本不是 Office 文件（txt 改名 docx 会撞上 POI 的
            // NotOfficeXmlFileException 等 RuntimeException）。
            log.error("DOCX 解析失败：{}", docxFile, e);
            throw new DocumentParseException("DOCX 文件损坏或格式无法解析");
        }
    }
}
