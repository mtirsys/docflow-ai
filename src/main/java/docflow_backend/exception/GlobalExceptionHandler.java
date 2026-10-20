package docflow_backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：整个应用所有 Controller 抛出的异常，最后都汇聚到这个类统一翻译成 HTTP 响应。
 *
 * @RestControllerAdvice = @ControllerAdvice + @ResponseBody：
 * 方法的返回值会被 Jackson 直接写成 JSON 响应体。
 *
 * 兜底原则：
 * - 4xx 是"客户端的请求有问题"，可以放心把原因写进 message；
 * - 5xx 是"服务器内部出了问题"，只返回一句通用提示，详细堆栈只写进日志，绝不返回给客户端
 *   （堆栈里可能带着 SQL、表名、类名、文件路径，泄露出去等于给攻击者画地图）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 参数校验失败（@Valid + @NotBlank/@Size 不通过）→ 400。
     * message 直接来自 DTO 注解里写的中文提示，例如"文档标题不能为空"。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException e, HttpServletRequest request) {

        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(msg -> msg != null && !msg.isBlank())
                .distinct()
                .collect(Collectors.joining("；"));
        if (message.isBlank()) {
            message = "请求参数校验失败";
        }

        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /**
     * 请求体读不懂 → 400。两种典型情况：
     * 1. JSON 本身语法错误（少个引号、多个逗号）；
     * 2. JSON 合法但字段类型对不上（比如把字符串传给了数字字段）。
     * 原始的解析错误信息可能包含 Jackson 内部细节，所以只返回通用提示。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(
            HttpMessageNotReadableException e, HttpServletRequest request) {

        return build(HttpStatus.BAD_REQUEST, "请求体格式错误：请检查 JSON 语法和字段类型", request);
    }

    /**
     * 路径参数类型不匹配（例如 /api/documents/abc，"abc" 塞不进 Long id）→ 400。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException e, HttpServletRequest request) {

        Class<?> requiredType = e.getRequiredType();
        String expected = requiredType == null ? "未知" : requiredType.getSimpleName();
        String message = "参数 " + e.getName() + " 格式错误，应为 " + expected;

        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /**
     * Service 层的业务参数校验失败（如 page、size 的范围）→ 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException e, HttpServletRequest request) {

        return build(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    /**
     * 文档不存在 → 404。由 DocumentService 抛出，是"客户端要的东西不存在"，
     * 不是服务器故障，绝不能落进 500 兜底。
     */
    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDocumentNotFound(
            DocumentNotFoundException e, HttpServletRequest request) {

        return build(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    /**
     * 请求了不存在的路径（没人接管的 URL）→ 404。保留 Spring Boot 4 的行为。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException e, HttpServletRequest request) {

        return build(HttpStatus.NOT_FOUND, "请求的接口不存在", request);
    }

    /**
     * 请求方法不支持（比如对 /api/documents/1 发 POST，但只定义了 GET/PUT/DELETE）→ 405。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e, HttpServletRequest request) {

        return build(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持：" + e.getMethod(), request);
    }

    /**
     * Content-Type 不支持（比如 POST 不带 application/json）→ 415。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException e, HttpServletRequest request) {

        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type 不支持，请使用 application/json", request);
    }

    /**
     * 最后的兜底：所有没被上面接住的异常 → 500。
     * 只写日志（带完整堆栈，供排查），响应体只给一句通用话，不泄露任何内部细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception e, HttpServletRequest request) {

        log.error("未处理的请求异常：{} {}", request.getMethod(), request.getRequestURI(), e);

        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误，请稍后重试", request);
    }

    /**
     * 所有处理器共用的响应拼装方法：状态码 + 状态短语 + 提示信息 + 请求路径。
     * request.getRequestURI() 拿到的是去掉域名后的路径，如 /api/documents/999。
     */
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
    }
}
