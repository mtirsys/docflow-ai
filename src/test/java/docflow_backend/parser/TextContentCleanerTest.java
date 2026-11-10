package docflow_backend.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Day 22：TextContentCleaner 的单元测试。
 *
 * 全项目最"便宜"的测试：被测对象不碰磁盘、不碰数据库、不碰 Spring，
 * 直接 new 出来喂字符串就能断言——纯函数组件的价值在测试上最直观。
 *
 * 断言分两类，后者更重要：
 * - "脏东西被清掉"（功能在干活）；
 * - "正常文字没被误删"（清洗坏了不报错，只能靠这些测试守门）。
 */
class TextContentCleanerTest {

    private final TextContentCleaner cleaner = new TextContentCleaner();

    @Test
    @DisplayName("null：返回空字符串，绝不把 null 传给下游")
    void nullReturnsEmptyString() {
        assertEquals("", cleaner.clean(null));
    }

    @Test
    @DisplayName("空字符串：返回空字符串")
    void emptyReturnsEmptyString() {
        assertEquals("", cleaner.clean(""));
    }

    @Test
    @DisplayName("普通文本：一字不改")
    void cleanTextPassesThroughUnchanged() {
        assertEquals("第一段\n\n第二段", cleaner.clean("第一段\n\n第二段"));
    }

    @Test
    @DisplayName("三种换行符（\\r\\n、\\r、\\f）统一为 \\n")
    void unifiesAllLineBreaks() {
        assertEquals("一\n二\n三\n四", cleaner.clean("一\r\n二\r三\f四"));
    }

    @Test
    @DisplayName("行首行尾空白被清理，行中间的空格保留")
    void trimsLineEdgesButKeepsInnerSpaces() {
        assertEquals("a b c\n第二行", cleaner.clean("   a b c   \n\t第二行\t"));
    }

    @Test
    @DisplayName("连续空行折叠为 1 个空行，段落间隔保留")
    void collapsesConsecutiveBlankLines() {
        assertEquals("第一段\n\n第二段", cleaner.clean("第一段\n\n\n\n\n\n第二段"));
    }

    @Test
    @DisplayName("多段文本：原有段落结构完整保留")
    void preservesParagraphStructure() {
        assertEquals("第一段。\n\n第二段。\n\n第三段。",
                cleaner.clean("第一段。\n\n\n\n第二段。\n\n第三段。"));
    }

    @Test
    @DisplayName("中文内容逐字保留；行首尾的全角空格被清掉")
    void preservesChineseAndStripsFullWidthSpaces() {
        assertEquals("中文标题\n正文内容", cleaner.clean("　中文标题　\n正文内容"));
    }

    @Test
    @DisplayName("Markdown 标题和列表结构保留")
    void preservesMarkdownHeadingsAndLists() {
        String md = "# 季度报告\n\n- 第一项\n- 第二项\n\n正文";
        assertEquals(md, cleaner.clean(md));
    }

    @Test
    @DisplayName("代码围栏内：缩进和连续空行原样保留（围栏保护）")
    void preservesCodeBlockIndentationAndBlankLines() {
        String withCode = "说明\n\n```java\n  int a = 1;\n\n\n  int b = 2;\n```\n\n结尾";
        assertEquals(withCode, cleaner.clean(withCode));
    }

    @Test
    @DisplayName("控制字符（NUL、DEL、BOM）被删除，正文不受影响")
    void removesControlCharacters() {
        assertEquals("正文完好", cleaner.clean("正\u0000\u0001文\u007F完\uFEFF好"));
    }

    @Test
    @DisplayName("文本整体首尾的空白和空行去掉")
    void stripsOverallEdges() {
        assertEquals("正文", cleaner.clean("\n\n   正文   \n\n"));
    }

    @Test
    @DisplayName("安全底线：正文文字一个不丢（只动空白和控制字符）")
    void neverRemovesActualText() {
        String result = cleaner.clean("  苹果 香蕉\r\n橙子\t西瓜\n\n\n\n\r\n葡萄  ");
        assertEquals("苹果 香蕉\n橙子\t西瓜\n\n葡萄", result);
    }
}
