package docflow_backend.mapper;

import docflow_backend.model.Document;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DocumentMapper {

    @Insert("""
        INSERT INTO documents (title, file_type)
        VALUES (#{title}, #{fileType})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Document document);

    @Select("""
        SELECT id, title, file_type, status, created_at
        FROM documents
        WHERE id = #{id}
        """)
    Document findById(Long id);

    @Select("""
        SELECT id, title, file_type, status, created_at
        FROM documents
        ORDER BY id DESC
        """)
    List<Document> findAll();

    @Delete("""
    DELETE FROM documents
    WHERE id = #{id}
    """)
    int deleteById(@Param("id") Long id);



    @Update("""
    UPDATE documents
    SET title = #{title},
        file_type = #{fileType}
    WHERE id = #{id}
    """)
    int updateById(
            @Param("id") Long id,
            @Param("title") String title,
            @Param("fileType") String fileType
    );
}