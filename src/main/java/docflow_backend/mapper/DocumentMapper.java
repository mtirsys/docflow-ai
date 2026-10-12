package docflow_backend.mapper;

import docflow_backend.model.Document;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

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
        ORDER BY id DESC
        """)
    List<Document> findAll();
}