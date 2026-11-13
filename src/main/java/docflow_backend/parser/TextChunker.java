package docflow_backend.parser;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Day 23：文本分块器——把 Day 22 清洗后的干净文本切成 RAG 用的 Chunk。
 *
 * 在流水线里的位置（上游是 TextContentCleaner 的产出，下游是未来的向量化）：
 * 干净文本 → 【这里】分块 → 每块独立计算向量、独立被检索。
 *
 * 设计要点（与 TextContentCleaner 同款哲学）：
 * - 纯函数组件：输入字符串、输出 List<TextChunk>，不碰磁盘、数据库、网络，
 *   因此可以脱离 Spring/MySQL 独立做单元测试；
 * - @Component：交给 Spring 创建和注入（Spring 会用无参构造器，默认 500/50）；
 * - 无状态：chunkSize/chunkOverlap 都用 final 固定，创建后不可变，
 *   多个请求共用同一个实例、互不干扰（天然线程安全）。
 *
 * 算法（"叠行李箱"模型）：
 * 1. 按 \n\n 把原文拆成段落（Day 22 保证段落之间是标准空行分隔）；
 * 2. 一段段往当前块里攒，攒到放不下就"封箱"成 Chunk，开新块；
 * 3. 新块开头补上一块结尾的 chunkOverlap 个字（重叠，保证边界上下文完整）；
 * 4. 单段自己就超过 chunkSize 时，段内退回固定步长硬切（步长 = chunkSize - chunkOverlap，
 *    步长恒 > 0，天然不会死循环）；最后一段剩余全保留，绝不动丢；
 * 5. 所有块都 ≤ chunkSize（预算把重叠也算在内），块尾几乎总落在段落边界上。
 */
@Component
public class TextChunker {

    /** 每块最多多少字符（预算上限，含重叠部分）。 */
    private final int chunkSize;

    /** 相邻块之间重叠的字符数。 */
    private final int chunkOverlap;

    /**
     * 无参构造器：Spring 用它创建默认实例（500/50）。
     * 存在多个构造器且都不加注解时，Spring 会回退到无参构造器。
     */
    public TextChunker() {
        this(500, 50);
    }

    /**
     * 测试和将来调参用：可以自选块大小和重叠值。
     *
     * 三道门口防线（先于一切切分动作）：
     * 1. chunkSize <= 0：一块都装不下，直接拦；
     * 2. chunkOverlap < 0：负数重叠没有意义，直接拦；
     * 3. chunkOverlap >= chunkSize：最关键的一道——硬切步长 = chunkSize - chunkOverlap，
     *    条件不成立时步长 ≤ 0，下一块起点永远前进不了 → 无限循环。
     *    防死循环不靠循环里打补丁，靠门口把病根拦下（同 Day 13 上传校验的思路）。
     *
     * 校验口径 IllegalArgumentException：复用 GlobalExceptionHandler
     * 现成的"参数非法 → 400"翻译，不发明新的错误机制。
     */
    public TextChunker(int chunkSize, int chunkOverlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0，收到的是 " + chunkSize);
        }
        if (chunkOverlap < 0) {
            throw new IllegalArgumentException("chunkOverlap 不能是负数，收到的是 " + chunkOverlap);
        }
        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "chunkOverlap 必须小于 chunkSize（收到 " + chunkOverlap + " >= " + chunkSize
                            + "）：步长 = chunkSize - chunkOverlap，条件不满足时切分位置永远不前进，会造成无限循环");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    /**
     * 把一篇干净文本切成有序的 Chunk 列表。
     *
     * 契约：
     * - 输入 null 或空字符串 → 返回空 List（List.of()），绝不返回 null，
     *   调用方拿到的是"空盒子"而不是炸弹（与 Cleaner 返回 "" 同一哲学）；
     * - 正常文本 → 返回的 List 保序：第 i 个元素就是 index == i 的块，
     *   原文全部有效内容都在块里，一块不丢。
     */
    public List<TextChunk> chunk(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<TextChunk> chunks = new ArrayList<>();

        // 攒块核心状态（就这两个变量，简单到不会出错）：
        // current：正在攒的"当前行李箱"，攒满了才封箱出货；
        // index：下一个块的编号，封箱一次 +1，保证 index == 块在 List 里的位置。
        StringBuilder current = new StringBuilder();
        int index = 0;

        // 拆段：Day 22 保证段落间是标准 \n\n，split 后每段是一行或几行连续文字。
        // 逐段处理 → 循环最多转"段落数"圈，没有嵌套、没有回跳，天然不无限循环。
        for (String paragraph : text.split("\n\n")) {
            // 防御性 strip：上游清洗过段首尾，但本组件独立调用时也保证拿到净段。
            // strip 只去掉段首尾空白，不动段内文字——"保留全部有效内容"不被破坏。
            String para = paragraph.strip();

            // 空段（原文首尾或中间的遗留空段）直接跳过，不占预算。
            if (para.isEmpty()) {
                continue;
            }

            if (para.length() > chunkSize) {
                // 分支 A：超长段——先封箱当前攒的块，再对这段独立硬切。
                if (current.length() > 0) {
                    chunks.add(new TextChunk(index++, current.toString()));
                    current.setLength(0);
                }
                int step = chunkSize - chunkOverlap; // 步长恒 >= 1（构造器已保证）
                // 硬切：每刀跨 step 字。相邻两刀的起点差 = step < chunkSize，
                // 所以前一刀的结尾 chunkOverlap 字会出现在后一刀的开头（重叠）。
                // Math.min 保证最后一刀切到文末为止——剩余全部保留，一个字不丢。
                for (int start = 0; start < para.length(); start += step) {
                    int end = Math.min(start + chunkSize, para.length());
                    chunks.add(new TextChunk(index++, para.substring(start, end)));
                }
            } else if (fitsInCurrent(current, para)) {
                // 分支 B：放得下——追加进当前箱，段与段之间用 \n\n 缝合（保持段落结构）。
                appendPara(current, para);
            } else {
                // 分支 C：放不下——封箱、开新箱、补重叠。
                String tail = current.length() <= chunkOverlap
                        ? current.toString()
                        : current.substring(current.length() - chunkOverlap);
                chunks.add(new TextChunk(index++, current.toString()));
                current.setLength(0);

                // 新箱开头补上上一箱结尾的 tail（重叠）。预算把重叠也算在内：
                // 若 tail 装进去就挤得 para 放不下了（para 太大、预算余量太小），
                // 则宁可不补重叠也不超预算——该段几乎占满一整箱，重叠的意义
                // 已经很小，这是"块大小恒 ≤ chunkSize"优先的显式取舍。
                if (tail.length() + 2 + para.length() <= chunkSize) {
                    current.append(tail).append("\n\n").append(para);
                } else {
                    current.append(para);
                }
            }
        }

        // 收尾：最后一箱哪怕只有一段，也要封箱出货——"最后一个 Chunk 不丢内容"的保证点。
        if (current.length() > 0) {
            chunks.add(new TextChunk(index++, current.toString()));
        }

        return chunks;
    }

    /**
     * 判断段落能否放进当前块。缝合符 \n\n 算 2 个字符，计入预算。
     * 空箱直接放（段落长度已保证 ≤ chunkSize）。
     */
    private boolean fitsInCurrent(StringBuilder current, String para) {
        if (current.length() == 0) {
            return true;
        }
        return current.length() + 2 + para.length() <= chunkSize;
    }

    /**
     * 追加段落：非空箱先补 \n\n 缝合符再接段落；空箱直接装。
     */
    private void appendPara(StringBuilder current, String para) {
        if (current.length() > 0) {
            current.append("\n\n");
        }
        current.append(para);
    }
}
