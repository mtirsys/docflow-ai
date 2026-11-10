package docflow_backend.exception;

/**
 * Day 20：文档内容解析失败的业务异常。
 *
 * 适用场景（文件在、类型也对，但内容解析不动）：
 * - PDF 文件损坏或结构不完整；
 * - PDF 加密且没有可用密码（打不开锁）；
 * - PDF 没有文字层（如扫描图片型 PDF），提取结果为空；
 * - PDF 页数超出解析能力上限。
 *
 * 和两位"近亲"的分工：
 * - IllegalArgumentException 说的是"参数不合法"（如 page、size、不支持的类型）；
 * - FileStorageException 说的是"存储层故障"（磁盘、IO，锅在服务器）；
 * - 本异常说的是"文件内容本身解析不动"——文件是客户端上传来的，
 *   锅主要在数据源头，和 404/500 都不一样，值得单独一个词。
 *
 * 继承 RuntimeException（非受检异常）：不需要在方法签名上层层声明 throws，
 * 与 DocumentNotFoundException 同款做法。Day 20 任务 D 会在 GlobalExceptionHandler
 * 里为它添加专门的翻译；在那之前它会暂时落入 500 兜底（开发中间态）。
 */
public class DocumentParseException extends RuntimeException {

    public DocumentParseException(String message) {
        super(message);
    }
}
