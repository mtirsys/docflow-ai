package docflow_backend.dto;

/**
 * Day 19：GET /api/documents/{id}/content 的 Service 层产出。
 *
 * 和 DocumentDownload 一样，Service 只交"业务包裹"，HTTP 包装留给 Controller。
 * 目前只支持 Markdown：纯文本内容读成字符串放进 JSON，客户端（如前端
 * 渲染器）拿到后可以直接处理，不需要再下载文件自己解析。
 *
 * 字段取舍：
 * - id / originalFilename：客户端定位和显示"这是哪个文档的什么文件"；
 * - content：UTF-8 读取的文件正文；
 * - characterCount：content 的字符数（content.length()），客户端可用于
 *   展示"共 N 字"，也方便测试断言；
 * - 刻意不含 storageKey：它是服务器内部的磁盘定位细节，不该暴露给客户端；
 * - 刻意不直接复用 Document 实体：实体是数据库表的映射（元数据），
 *   响应是客户端需求（含内容），两者职责不同，见 DocumentDownload 先例。
 */
public record DocumentContentResponse(
        Long id,
        String originalFilename,
        String content,
        int characterCount
) {
}
