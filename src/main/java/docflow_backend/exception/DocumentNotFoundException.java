package docflow_backend.exception;

/**
 * 业务异常：查询/修改/删除的文档不存在。
 *
 * 以前这个情况由 Controller 返回 null 判断 + 手写 404，现在改成：
 * Service 层发现"没有这条数据"就直接抛出这个异常，
 * 由 GlobalExceptionHandler 统一翻译成 HTTP 404。
 *
 * 继承 RuntimeException（非受检异常）：不需要在方法签名上层层声明 throws，
 * Spring 对 @RestControllerAdvice 捕获它没有任何障碍。
 */
public class DocumentNotFoundException extends RuntimeException {

    private final Long documentId;

    public DocumentNotFoundException(Long documentId) {
        super("文档不存在：id=" + documentId);
        this.documentId = documentId;
    }

    /**
     * Day 17：文档记录存在、但对应的实体（目前是磁盘文件）拿不到时使用。
     * 默认消息"文档不存在"在这种场景下是撒谎，所以允许调用方给出如实的描述。
     * documentId 照常保留，方便日志和排查定位。
     */
    public DocumentNotFoundException(Long documentId, String message) {
        super(message);
        this.documentId = documentId;
    }

    public Long getDocumentId() {
        return documentId;
    }
}
