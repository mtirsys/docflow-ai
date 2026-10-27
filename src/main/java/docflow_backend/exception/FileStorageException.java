package docflow_backend.exception;

/**
 * 文件存储失败：磁盘不可写、目录无法创建、写入中断等。
 *
 * 和 IllegalArgumentException（客户端文件不合法 → 400）不同，
 * 存储失败是"服务器自己出了状况"，所以由 GlobalExceptionHandler 翻译成 500，
 * 响应只给通用提示，磁盘路径等细节只写日志。
 */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message) {
        super(message);
    }

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
