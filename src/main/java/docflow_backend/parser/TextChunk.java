package docflow_backend.parser;

/**
 * Day 23：文本分块的结果载体——一个 Chunk 的"快递盒"。
 *
 * 在流水线里的位置：TextChunker 是"切菜师傅"，本类是它交出的"一盘菜"。
 * 每盘菜装两样东西：
 * - index：这是第几块，从 0 开始编号，保证顺序、支持将来溯源和定位；
 * - text：这一块的文字内容（经 Day 22 清洗后的干净文本切出来的片段）。
 *
 * 为什么用 record 而不是普通 class（同 DocumentContentResponse 的取舍）：
 * - 它只是"数据搬运工"，没有业务行为，record 一行搞定构造器、
 *   读取方法、equals、toString，不用手写样板代码；
 * - record 天生不可变：创建之后 index 和 text 都改不了，
 *   多个请求各拿各的 Chunk 互不干扰（和 TextContentCleaner 的无状态同理）。
 */
public record TextChunk(int index, String text) {
}
