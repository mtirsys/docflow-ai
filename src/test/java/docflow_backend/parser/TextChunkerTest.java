package docflow_backend.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day 23：TextChunker 的单元测试。
 *
 * 和 TextContentCleanerTest 同款：被测对象是纯函数组件，
 * 直接 new 出来喂字符串就能断言，不碰 Spring/MySQL。
 *
 * 断言分两类：
 * - "切得对"（块数、边界、重叠、收尾）——算法在干活；
 * - "内容不丢"（收尾块保留全部剩余、块大小永不超预算）——
 *   分块坏了不报错，只会静默丢内容，这类断言是唯一的守门员。
 *
 * 测试普遍用小参数 new TextChunker(20, 5)：20 字一块、重叠 5 字，
 * 切口和重叠肉眼可见，断言不用对天文数字。
 */
class TextChunkerTest {

    /** 常用的小参数实例：chunkSize=20，chunkOverlap=5。 */
    private final TextChunker chunker = new TextChunker(20, 5);

    @Test
    @DisplayName("null：返回空列表，绝不返回 null")
    void nullReturnsEmptyList() {
        List<TextChunk> result = chunker.chunk(null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("空字符串：返回空列表")
    void emptyReturnsEmptyList() {
        assertTrue(chunker.chunk("").isEmpty());
    }

    @Test
    @DisplayName("小于 chunkSize 的文本：只出一块，内容原样、index 为 0")
    void shortTextSingleChunk() {
        List<TextChunk> result = chunker.chunk("面向对象有三大特性");
        assertEquals(1, result.size());
        assertEquals(0, result.get(0).index());
        assertEquals("面向对象有三大特性", result.get(0).text());
    }

    @Test
    @DisplayName("正好等于 chunkSize 的文本：恰好一块，一字不多一字不少")
    void exactlyChunkSizeSingleChunk() {
        String text = "a".repeat(20); // 正好 20 字，压线
        List<TextChunk> result = chunker.chunk(text);
        assertEquals(1, result.size());
        assertEquals(20, result.get(0).text().length());
        assertEquals(text, result.get(0).text());
    }

    @Test
    @DisplayName("多段落攒块：前两段合箱，第三段放不下触发封箱，两块收场")
    void accumulatesParagraphsUntilBudget() {
        // 8+2+8=18 ≤ 20：A、B 合箱；再放 C 要 18+2+8=28 > 20 → 封箱。
        String text = "AAAAAAAA\n\nBBBBBBBB\n\nCCCCCCCC";
        List<TextChunk> result = chunker.chunk(text);

        assertEquals(2, result.size());
        assertEquals("AAAAAAAA\n\nBBBBBBBB", result.get(0).text());
        // 块尾落在段落边界——"块尾几乎总是完整的段落"的实证。
        assertTrue(result.get(0).text().endsWith("BBBBBBBB"));
    }

    @Test
    @DisplayName("重叠：第 2 块的开头 = 第 1 块结尾的 5 字（overlap 规则）")
    void overlapBetweenConsecutiveChunks() {
        String text = "AAAAAAAA\n\nBBBBBBBB\n\nCCCCCCCC";
        List<TextChunk> result = chunker.chunk(text);

        String first = result.get(0).text();      // "AAAAAAAA\n\nBBBBBBBB"
        String second = result.get(1).text();     // "BBBBB\n\nCCCCCCCC"

        // 第 1 块结尾的 5 字（BBBBB）必须出现在第 2 块开头——重叠存在。
        assertTrue(first.length() >= 5);
        String tail = first.substring(first.length() - 5);
        assertTrue(second.startsWith(tail));
        assertEquals("BBBBB\n\nCCCCCCCC", second);
    }

    @Test
    @DisplayName("超长单段硬切：相邻刀口重叠，最后一刀保留全部剩余")
    void hardSplitsOversizedParagraph() {
        String para = "X".repeat(45); // 45 字单段，chunkSize=20 → 必须硬切
        List<TextChunk> result = chunker.chunk(para);

        // 步长 = 20-5 = 15：刀口起点 0、15、30 → 三块。
        assertEquals(3, result.size());
        assertEquals(para.substring(0, 20), result.get(0).text());
        assertEquals(para.substring(15, 35), result.get(1).text());
        // 最后一刀 [30, 45)：只有 15 字，但剩余全保留，一个 X 不丢。
        assertEquals(para.substring(30, 45), result.get(2).text());
    }

    @Test
    @DisplayName("最后一个 Chunk 保留全部剩余内容（中文长段）")
    void lastChunkKeepsAllRemainder() {
        String para = "春眠不觉晓".repeat(9); // 45 个汉字单段
        List<TextChunk> result = chunker.chunk(para);

        assertEquals(3, result.size());
        // 末块 = 第 30 字起的全部 15 字，且以原文结尾收尾——不截、不丢。
        assertEquals(para.substring(30, 45), result.get(result.size() - 1).text());
        assertTrue(result.get(result.size() - 1).text().endsWith("晓"));
    }

    @Test
    @DisplayName("中文短文：整段保留、逐字完整")
    void chineseShortTextPreserved() {
        String text = "封装、继承、多态";
        List<TextChunk> result = chunker.chunk(text);
        assertEquals(1, result.size());
        assertEquals(text, result.get(0).text());
    }

    @Test
    @DisplayName("英文多段落：重叠生效；预算不足时弃重叠保大小上限（显式取舍）")
    void englishParagraphsWithOverlapAndTradeOff() {
        String text = "Spring Boot\n\nmakes Java\n\ndevelopment easy";
        List<TextChunk> result = chunker.chunk(text);

        assertEquals(3, result.size());

        // 块 0：只有 "Spring Boot"（再放 makes Java 要 11+2+10=23 > 20）。
        assertEquals("Spring Boot", result.get(0).text());
        // 块 1：开头补上块 0 结尾 5 字 " Boot"（重叠），再装 makes Java。
        assertEquals(" Boot\n\nmakes Java", result.get(1).text());
        // 块 2：tail(5)+2+16=23 > 20，重叠放不下 → 弃重叠只装段落（代码注释里的取舍分支）。
        assertEquals("development easy", result.get(2).text());

        // 不变量：无论怎么取舍，任何块都不许超过 chunkSize。
        for (TextChunk chunk : result) {
            assertTrue(chunk.text().length() <= 20);
        }
    }

    @Test
    @DisplayName("保序：第 i 个元素的 index 恰好是 i")
    void indexesAreSequential() {
        String text = "春眠不觉晓".repeat(9);
        List<TextChunk> result = chunker.chunk(text);

        for (int i = 0; i < result.size(); i++) {
            assertEquals(i, result.get(i).index());
        }
    }

    @Test
    @DisplayName("遗留空段被跳过，内容不受影响")
    void skipsBlankParagraphs() {
        // 中间夹了连续空行（正常上游清洗会折叠，这里验证组件独立调用也安全）。
        String result = chunker.chunk("A\n\n\n\nB").get(0).text();
        assertEquals("A\n\nB", result);
    }

    @Test
    @DisplayName("默认参数（500/50）可用：1200 字单段切成 3 块，块大小不超 500")
    void defaultConstructorWorks() {
        TextChunker defaultChunker = new TextChunker();
        String text = "字".repeat(1200); // 无段落，单段硬切
        List<TextChunk> result = defaultChunker.chunk(text);

        // 步长 450：起点 0、450、900 → 3 块（500 + 500 + 300）。
        assertEquals(3, result.size());
        for (TextChunk chunk : result) {
            assertTrue(chunk.text().length() <= 500);
        }
        assertEquals(text.substring(900), result.get(2).text());
    }

    @Test
    @DisplayName("非法参数：chunkSize <= 0 拒绝")
    void rejectsNonPositiveChunkSize() {
        assertThrows(IllegalArgumentException.class, () -> new TextChunker(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new TextChunker(-10, 5));
    }

    @Test
    @DisplayName("非法参数：负数 overlap 拒绝")
    void rejectsNegativeOverlap() {
        assertThrows(IllegalArgumentException.class, () -> new TextChunker(20, -1));
    }

    @Test
    @DisplayName("非法参数：overlap >= chunkSize 拒绝（死循环防线）")
    void rejectsOverlapNotSmallerThanChunkSize() {
        assertThrows(IllegalArgumentException.class, () -> new TextChunker(20, 20));
        assertThrows(IllegalArgumentException.class, () -> new TextChunker(20, 25));
    }
}
