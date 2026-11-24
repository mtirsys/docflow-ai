package docflow_backend.exception;

/**
 * Day 30：调用外部聊天模型失败。
 *
 * 它与“没有检索结果”不同：没有资料是正常业务结果；本异常表示百炼调用、
 * 网络或模型响应本身出了问题，不能伪装成“资料不足”。
 */
public class ChatModelCallException extends RuntimeException {

    public ChatModelCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
