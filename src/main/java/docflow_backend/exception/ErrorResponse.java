package docflow_backend.exception;

/**
 * 统一错误响应体：所有接口出错时都返回这个形状的 JSON。
 *
 * 示例：
 * {
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "文档标题不能为空",
 *   "path": "/api/documents"
 * }
 *
 * 用 record 而不是普通类：字段少、只读、Jackson 能自动按组件名序列化成 JSON。
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        String path
) {
}
