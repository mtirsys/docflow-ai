package docflow_backend.model;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

public class Document {

    private Long id;

    @NotBlank(message = "title must not be blank")
    private String title;

    private String fileType;
    private String status;
    private LocalDateTime createdAt;

    /**
     * Required by Jackson when binding a JSON request body and by MyBatis when
     * materialising query results.
     */
    public Document() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
