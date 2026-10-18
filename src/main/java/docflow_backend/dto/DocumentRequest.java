package docflow_backend.dto;

import docflow_backend.model.Document;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 请求体 DTO：只接收客户端允许提交的字段（title、fileType），
 * 校验规则集中在这里，与数据库实体 Document 解耦。
 */
public class DocumentRequest {

    @NotBlank(message = "must not be blank")
    @Size(max = 255, message = "must not exceed 255 characters")
    private String title;

    @NotBlank(message = "must not be blank")
    @Size(max = 50, message = "must not exceed 50 characters")
    private String fileType;

    /**
     * Jackson 绑定 JSON 请求体时需要空构造方法。
     */
    public DocumentRequest() {
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }
}
