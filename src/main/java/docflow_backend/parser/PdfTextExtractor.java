package docflow_backend.parser;

import docflow_backend.exception.DocumentParseException;
import docflow_backend.exception.FileStorageException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Day 20：PDF 文本提取器——整个项目里唯一知道 PDFBox 存在的类。
 *
 * 在架构里的位置（和两位邻居的分工）：
 * - LocalFileStorageService 负责"文件在哪、能不能读"（安全定位，交来验过货的 Path）；
 * - 这里负责"PDF 里有什么字"（拿到 Path 后用 PDFBox 把文字抠出来）；
 * - DocumentService 负责编排：MD 走 readText，PDF 走这里。
 *
 * 设计取舍：
 * - 入参是 Path 而不是 storageKey：路径安全校验属于存储层，解析器不碰安全、
 *   也不碰数据库，因此可以脱离 Spring/MySQL 独立做单元测试（给个临时文件就能测）；
 * - @Component 而不是 @Service：功能上两者等价（都是"交给 Spring 创建和管理"），
 *   @Service 只是个语义标签（"我是业务服务"）；本类是单一职责的技术组件，
 *   用 @Component 更贴切。将来 DocxTextExtractor 也照这个模式建在 parser 包里；
 * - 不做 OCR：扫描图片型 PDF 没有文字层，提取结果为空时如实报错，不做图像识别。
 */
@Component
public class PdfTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /**
     * 与上传白名单、readText 的 10 MB 口径一致。
     * 上传时已限过一次，这里是第二道防线：不信任数据库里的历史数据。
     */
    private static final long MAX_PDF_BYTES = 10L * 1024 * 1024;

    /**
     * 页数保护线：防止"体积小但页数海量"的构造文件把解析拖垮
     * （10 MB 的文字型 PDF 一般远到不了几百页）。经验值，可按需调整。
     */
    private static final int MAX_PAGES = 300;

    /**
     * 把一份 PDF 解析成纯文本。
     *
     * 三道资源防线，从便宜到贵依次拦截：
     * 1. 先看文件大小（只读元数据，最便宜）——超限直接拒绝，根本不打开文件；
     * 2. Loader.loadPDF 打开后再看页数——超限拒绝，不进入逐页提取；
     * 3. PDFTextStripper 逐页提取——真正的重活，走到这步说明前两关都过了。
     *
     * try-with-resources 保证 PDDocument 在成功和异常两种情况下都会关闭：
     * 它在内存里持有解析出的页面对象和字体缓冲，靠垃圾回收"想起来再回收"
     * 并不可靠，高并发下会累积成内存泄漏。
     *
     * 加密的说明：PDFBox 加载时会自动尝试用"空密码"解锁——大量 PDF 只是
     * 设置了所有者口令（限制打印/复制），空用户口令就能打开，这类文件可以正常提取；
     * 只有真正设了打开密码的文件才会抛 InvalidPasswordException，如实报错。
     */
    public String extract(Path pdfFile) {
        // 防线一：文件大小。先于一切解析动作——超限的文件连打开都不打开。
        try {
            long size = Files.size(pdfFile);
            if (size > MAX_PDF_BYTES) {
                log.error("PDF 超出解析大小上限，已拒绝：{}（{} 字节）", pdfFile, size);
                throw new FileStorageException("PDF 文件过大，拒绝解析");
            }
        } catch (IOException e) {
            log.error("解析前检查 PDF 文件大小失败：{}", pdfFile, e);
            throw new FileStorageException("PDF 文件读取失败", e);
        }

        // Loader.loadPDF(File) = "拆包裹"：把磁盘文件解析成内存里的 PDDocument。
        // PDFBox 3.x 必须走 Loader；2.x 教程里 PDDocument.load(file) 的写法已删除。
        try (PDDocument document = Loader.loadPDF(pdfFile.toFile())) {
            // 防线二：页数上限。
            int pages = document.getNumberOfPages();
            if (pages > MAX_PAGES) {
                throw new DocumentParseException(
                        "PDF 页数过多（" + pages + " 页，上限 " + MAX_PAGES + " 页）");
            }

            // PDFTextStripper = "抄写员"：逐页把"在某坐标画某字"的指令
            // 还原成按阅读顺序排列的普通字符串。getText 返回全文文本。
            String text = new PDFTextStripper().getText(document);

            // 防线三之后的一块"诚实检查"：提取结果为空（扫描件没有文字层）。
            // 用 isBlank 而不是 isEmpty：PDFTextStripper 用 \f（换页符）做页分隔，
            // "只有页分隔符、没有文字"的扫描件产出形如 "\f\f\f"，isBlank 能正确识别。
            if (text == null || text.isBlank()) {
                throw new DocumentParseException(
                        "PDF 中没有可提取的文本（可能是扫描图片型 PDF，暂不支持 OCR）");
            }
            return text;

        } catch (InvalidPasswordException e) {
            // InvalidPasswordException 是 IOException 的子类，必须放在 IOException 之前，
            // 否则会被下面更宽的分支抢先接住，把"加密"误报成"损坏"。
            log.warn("拒绝解析加密 PDF：{}", pdfFile);
            throw new DocumentParseException("PDF 已加密，无法提取文本");
        } catch (IOException e) {
            // 走到这里说明不是密码问题，而是文件本身打不开/读不动：
            // 内容损坏、结构不完整、根本不是 PDF 等。
            log.error("PDF 解析失败：{}", pdfFile, e);
            throw new DocumentParseException("PDF 文件损坏或格式无法解析");
        }
    }
}
