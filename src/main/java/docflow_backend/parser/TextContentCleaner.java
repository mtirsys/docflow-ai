package docflow_backend.parser;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Day 22：统一文本清洗器——三种格式（MD/PDF/DOCX）提取出文本后的公共"洗货"环节。
 *
 * 在流水线里的位置（上游是三位"拆包师傅"，下游是 DocumentContentResponse）：
 * 文件解析（拿到字）→ 【这里】文本清洗（把字排列干净）→ 打包响应。
 *
 * 设计要点：
 * - 纯函数式组件：输入字符串、输出字符串，不碰磁盘、不碰数据库、不碰网络，
 *   因此可以脱离 Spring/MySQL 独立做单元测试（比 readContent 的测试便宜得多）；
 * - @Component：和 PdfTextExtractor / DocxTextExtractor 同款——交给 Spring 创建和注入，
 *   第四步整合时 DocumentService 的构造函数会多认识一位同事；
 * - 无状态：不保存任何成员变量，多个请求同时调用互不干扰（天然线程安全）。
 *
 * 安全边界（清洗坏了不报错，只会静默毁内容，所以每条规则都必须保守）：
 * - 不删除任何正常文字（包括中文和全部 Unicode 字符）；
 * - 不把换行全部删除（段落结构是阅读和将来 RAG 分块的命根子）；
 * - Markdown 代码块内的行原样保留（缩进是内容的一部分，围栏保护策略）。
 */
@Component
public class TextContentCleaner {

    /**
     * 清洗一段原始文本。
     *
     * 输入：三条解析路（readText / PDFBox / POI）任一条产出的原始文本；
     * 处理：依次应用四组保守规则——换行统一 → 控制字符删除 →
     * 行首尾清理与空行折叠（围栏保护）→ 整体首尾 trim；
     * 输出：换行统一、无噪音空白的干净文本——正文内容绝不比输入少。
     *
     * null 和空字符串的契约：返回空字符串 ""，绝不返回 null——
     * 调用方（DocumentService）拿到的永远是合法字符串，不需要再判空。
     */
    public String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }

        String text = unifyLineBreaks(raw);               // 换行统一（含 \f 转换行）
        text = removeControlChars(text);                  // 控制字符删除
        text = trimLinesOutsideCodeFences(text);          // 行首尾清理 + 空行折叠（围栏保护）
        return text.strip();                              // 整体首尾 trim
    }

    /**
     * 规则一：统一换行符。三种"行结束"写法全部归一到 \n。
     *
     * 1. \r\n（Windows：回车 + 换行，两个字符）→ \n；
     * 2. \r（老式 Mac：单独一个回车字符）→ \n；
     * 3. \f（换页符，PDFBox 用它分隔页）→ \n——按既定决策把页边界降级为段落边界，
     *    而不是直接删除：删了会让上一页结尾和下一页开头粘连成一行。
     *
     * 顺序陷阱：必须先替换两字符的 \r\n，再替换单字符的 \r。
     * 反过来做会把每个 \r\n 拆成 \n\n——Windows 文件的每一行之间都多出一个空行，
     * 整篇文档凭空"加倍变松"，而且不报错、不崩溃，非常隐蔽。
     *
     * 方法选择：String.replace 是"字面替换"（把参数当普通文字去找），
     * replaceAll 才是"正则替换"（第一个参数按正则语法解析）。这里找的都是
     * 具体字符，用 replace 更直白，也不用担心正则特殊字符（如 . 和 *）搞鬼。
     * 注意两个重载：replace("\r\n", "\n") 收的是字符串参数（CharSequence），
     * replace('\r', '\n') 收的是单个字符参数——效果都是字面替换。
     */
    private String unifyLineBreaks(String text) {
        return text
                .replace("\r\n", "\n")   // Windows 换行 → 标准换行
                .replace('\r', '\n')     // 遗留的单独回车 → 标准换行
                .replace('\f', '\n');    // 换页符 → 标准换行（页边界 = 段落边界）
    }

    /**
     * 规则四：删除明确无意义的控制字符。
     *
     * 放在行清理之前——一行全是控制字符的"假内容行"先被删成空行，
     * 正好被后面的空行折叠收走；顺序反了会留下漏网空行。
     *
     * 正则 "[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F\\uFEFF]" 是一张通缉名单：
     * - [] 是"字符集合"：名单里任意一个字符都算命中；
     * - \\x00-\\x08 是范围写法：编号 00 到 08 的字符全部列入；
     * - 名单刻意排除 \\x09(制表符 \\t) 和 \\x0A(换行 \\n)——有意义的格式字符
     *   （\\r 回车和 \\f 换页在更早的规则里已经统一成 \\n，这里就算删也无货可删）；
     * - \\x7F 是 DEL（删除符，最老的控制字符）；
     * - \\uFEFF 是 BOM：Windows 记事本在 UTF-8 文件开头加的"隐形签名"，
     *   不删它就会成为正文第一个看不见的字符。
     *
     * replaceAll 第一个参数是正则（和 replace 的"字面量"约定不同），
     * 第二个参数 "" 表示"替换成空" = 删除。
     */
    private String removeControlChars(String text) {
        return text.replaceAll(
                "[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F\\uFEFF]", "");
    }

    /**
     * 规则二 + 规则三：行首行尾清理 + 连续空行折叠（都带围栏保护）。
     *
     * 逐行扫描，boolean 开关记录"是否在 ``` 围栏内"，int 计数器记录
     * "围栏外已连续遇到几个空行"：
     * - 围栏外的普通行：清理行首行尾；连续空行只保留第 1 个（段落间隔），
     *   第 2 个起整行丢弃（连同它的换行符一起，不进清单就不会被缝回去）；
     * - 围栏内的代码行：整行原样保留，空行也是内容，一个不丢；
     * - 围栏行（以 ``` 开头）：翻转开关，计数器归零，行本身按围栏外规则清理。
     *
     * 拼接方式：把要保留的行放进 List 清单，最后 String.join("\n", ...) 缝成一篇。
     * 好处：被丢弃的行不在清单里，换行符跟着一起消失，不需要手工管理
     * "哪行后面补 \n、哪行不补"——join 自动只在行与行之间放分隔符。
     */
    private String trimLinesOutsideCodeFences(String text) {
        String[] lines = text.split("\n", -1);
        boolean inFence = false;
        int blankRun = 0;
        List<String> kept = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.strip();

            if (trimmed.startsWith("```")) {
                inFence = !inFence;      // 围栏行：翻牌子
                blankRun = 0;
                kept.add(trimmed);
            } else if (inFence) {
                blankRun = 0;            // 代码行：计数器也要归零（它打断了"连续空行"）
                kept.add(line);          // 原样保留，一字不动
            } else if (trimmed.isEmpty()) {
                blankRun++;              // 又一个空行：连续计数 +1
                if (blankRun <= 1) {
                    kept.add(trimmed);   // 第 1 个空行保留（段落间隔）
                }
                // 第 2 个及以后：不进清单 = 连行带换行一起丢弃
            } else {
                blankRun = 0;            // 正常文字：连续空行被打断，归零
                kept.add(trimmed);
            }
        }

        return String.join("\n", kept);
    }
}
