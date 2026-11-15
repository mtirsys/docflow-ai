package docflow_backend.model;

import java.time.LocalDateTime;

/**
 * Day 24：document_chunks 表的实体——一个文本块在数据库里的"存档形态"。
 *
 * 和 TextChunk（parser 包的 record）的关系：
 * TextChunk 是"车间里的半成品"——切菜师傅 TextChunker 刚切出来，只有
 * index 和 text 两个字段，谁知道它属于哪篇文档？没人；
 * 本类是"入库后的成品"——补上了 documentId（属于谁）、charCount（多长）、
 * id 和 createdAt（数据库户口信息）。Service 的活就是把半成品转成成品。
 *
 * 为什么不用 record 而是普通 class（同 Document 的取舍）：
 * MyBatis 映射查询结果时按"无参构造器 + setter"逐个字段填值（表里
 * document_id 列靠 map-underscore-to-camel-case 自动对上 documentId 属性），
 * record 没有无参构造器和 setter，要额外配置构造器映射才能工作。
 * 建实体和 Document 保持同款，查询、插入都走现成的路。
 */
public class DocumentChunk {

    /** 本表自增主键：这一行自己的身份证，UPDATE/DELETE 某一块时用它定位。 */
    private Long id;

    /** 属于哪篇文档（documents.id 的值），见外键 fk_chunk_document。 */
    private Long documentId;

    /** 在所属文档里的第几块，从 0 开始，与 TextChunk.index() 对应。 */
    private Integer chunkIndex;

    /** 这一块的正文（Day 22 清洗后的干净文本切出来的片段）。 */
    private String content;

    /** 这一块的字符数（content.length()，字符不是字节），冗余存储换统计速度。 */
    private Integer charCount;

    /** 入库时间：Java 不填，由数据库 DEFAULT CURRENT_TIMESTAMP 自动生成。 */
    private LocalDateTime createdAt;

    /** MyBatis 映射查询结果、Jackson 反序列化都要求的无参构造器。 */
    public DocumentChunk() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
