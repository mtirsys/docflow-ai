package docflow_backend.mapper;

import docflow_backend.model.DocumentChunk;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Day 24：document_chunks 表的数据访问接口，写法与 DocumentMapper 同款——
 * SQL 用注解直接写在接口方法上，没有 XML。
 *
 * 三个方法的分工：
 * - insertBatch：批量插入一篇文档切出的所有块（一条多行 INSERT）；
 * - deleteByDocumentId：重新生成前清掉旧块（"先删后插"策略的前半步）；
 * - findByDocumentId：按文档取回全部块，ORDER BY chunk_index 保证顺序，
 *   为 Day 25+ 的向量化/检索做准备，也让测试能验证落库结果。
 */
@Mapper
public interface DocumentChunkMapper {

    /**
     * 批量插入：把一批块拼成一条多行 INSERT（不是循环发 N 条）。
     *
     * <script> + <foreach> 是 MyBatis 注解 SQL 里动态拼 SQL 的固定套路：
     * foreach 逐个遍历 chunks 列表，每块生成一行 (#{...})，行与行之间用逗号
     * 分隔（separator=","）。最终生成的 SQL 形如：
     *   INSERT INTO document_chunks (...) VALUES (?,?,?,?), (?,?,?,?), ...
     * 一次网络往返写完全部块，比循环 N 次单条 INSERT 快且省连接。
     *
     * 调用约定（由 Service 保证）：chunks 非空——foreach 遍历空列表会生成
     * "VALUES" 后面什么都没有的残缺 SQL，数据库直接报语法错误。
     * 空列表该不该写库由业务层判断，本方法不替它兜底。
     */
    @Insert("""
        <script>
        INSERT INTO document_chunks (document_id, chunk_index, content, char_count)
        VALUES
        <foreach collection="chunks" item="chunk" separator=",">
            (#{chunk.documentId}, #{chunk.chunkIndex}, #{chunk.content}, #{chunk.charCount})
        </foreach>
        </script>
        """)
    int insertBatch(@Param("chunks") List<DocumentChunk> chunks);

    /**
     * 删掉一篇文档的全部旧块，重新生成前调用。
     * 返回删除行数（不是 boolean）：0 行合法——文档可能从没生成过块。
     */
    @Delete("""
        DELETE FROM document_chunks
        WHERE document_id = #{documentId}
        """)
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * 按文档取回全部块，chunk_index 升序——"顺序正确"由它保证。
     * id 不出现在 WHERE 里：id 是全表身份证，不知道值；业务语义是
     * "这篇文章的第几块"，永远用 (document_id, chunk_index) 定位。
     */
    @Select("""
        SELECT id, document_id, chunk_index, content, char_count, created_at
        FROM document_chunks
        WHERE document_id = #{documentId}
        ORDER BY chunk_index
        """)
    List<DocumentChunk> findByDocumentId(@Param("documentId") Long documentId);

    /**
     * Day 29：按主键 id 批量取回 Chunk——语义检索的"回查原文"用。
     *
     * 为什么需要它：Qdrant 检索返回的是一组 chunkId（payload 里带的），
     * 要把这些 id 一次性翻译成可读原文，不该循环发 N 次单条 SELECT。
     * 一条带 IN (?, ?, ...) 的 SQL 跑完，一次网络往返拿全。
     *
     * 不保证顺序：返回顺序由 MySQL 决定（通常按主键），调用方（SearchService）
     * 按 Qdrant 给的 score 顺序重新组装结果，不依赖这里的顺序。
     *
     * 调用约定（与 insertBatch 同款）：ids 非空。foreach 遍历空列表会生成
     * "WHERE id IN ()" 的残缺 SQL 报语法错——空列表该不该查由业务层判断，
     * SearchService 在 Qdrant 返回空时直接返回空 List，根本不调到这里。
     */
    @Select("""
        <script>
        SELECT id, document_id, chunk_index, content, char_count, created_at
        FROM document_chunks
        WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
        </script>
        """)
    List<DocumentChunk> findByIds(@Param("ids") List<Long> ids);
}
