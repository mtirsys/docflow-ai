package docflow_backend.dto;

import org.springframework.core.io.Resource;

/**
 * Day 17：GET /api/documents/{id}/download 的 Service 层产出。
 *
 * Service 负责"业务是什么"，Controller 负责"HTTP 怎么说"，
 * 所以 Service 不返回 ResponseEntity（那是 HTTP 概念），
 * 而是交出这个"包裹"，由 Controller 拆开并组装下载响应头。
 *
 * - resource：文件内容的抽象（可读字节流），来自 LocalFileStorageService.load()；
 * - originalFilename：从数据库查出的原始文件名，只用于
 *   Content-Disposition（浏览器保存对话框里显示的名字），绝不用于磁盘定位。
 */
public record DocumentDownload(
        Resource resource,
        String originalFilename
) {
}
