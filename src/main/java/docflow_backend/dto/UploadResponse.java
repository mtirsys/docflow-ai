package docflow_backend.dto;

/**
 * Day 15：POST /api/documents/upload 的成功响应。
 *
 * 和 UploadCheckResponse（stored=false）对照着看，就是"只验不收"和"验完真收"的区别：
 * - originalFilename / size / contentType：与 upload-check 口径一致；
 * - storageKey：服务器生成的唯一存储名，将来要存进数据库、用来找回这个文件；
 * - stored：固定为 true——这次文件真的落盘了。
 *
 * 注意这里刻意不返回磁盘绝对路径：调用方只需要 storageKey，
 * 知道文件在 D:/docflow-storage 的哪个角落对前端没有用处，还泄露服务器结构。
 */
public record UploadResponse(
        Long id,
        String originalFilename,
        long size,
        String contentType,
        String storageKey,
        boolean stored,
        String message
) {
}
