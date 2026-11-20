package docflow_backend.exception;

/**
 * Day 28：Qdrant 存储操作失败的业务异常——把 gRPC 层的"难懂原话"
 * 翻译成"知道哪里坏了"的中文事实。
 *
 * 为什么不直接把底层异常抛出去：
 * Qdrant 官方客户端的调用全部是 ListenableFuture.get() 风格，失败时抛上来的
 * 是 ExecutionException——它的 message 是 "io.grpc.StatusRuntimeException:
 * UNAVAILABLE: io exception ..." 这种技术黑话，还裹着一层未来已完成的壳。
 * 调用方（VectorizationService）和运维需要的是"连不上哪台、哪个 collection、
 * 哪个操作"，所以存储层统一翻译后再抛，原始异常用 cause 保留着不丢线索。
 *
 * 目前走 GlobalExceptionHandler 的兜底通道（500）——向量库故障属于
 * "稍后重试也许能好"的服务器侧问题，与 DocumentParseException（422）、
 * DocumentNotFoundException（404）的语义都不同，不共用它们的翻译。
 */
public class QdrantStorageException extends RuntimeException {

    public QdrantStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
