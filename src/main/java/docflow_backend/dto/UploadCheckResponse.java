package docflow_backend.dto;

/**
 * Day 13：upload-check 接口的成功响应 DTO。
 *
 * 只描述"这次校验看到了什么"，绝不包含任何存储路径或服务器内部信息：
 * - originalFilename：客户端上报的原始文件名（已剥掉可能夹带的路径部分）；
 * - size：文件内容的字节数，由服务器解析 multipart 报文后统计，不信任客户端；
 * - contentType：请求里自带的 Content-Type（注意：这是客户端"自报家门"，可以伪造）；
 * - stored：固定为 false——Day 13 只做验证，文件没有落盘，数据库也没有新记录。
 */
public class UploadCheckResponse {

    private final String originalFilename;
    private final long size;
    private final String contentType;
    private final boolean stored;
    private final String message;

    public UploadCheckResponse(String originalFilename, long size, String contentType) {
        this.originalFilename = originalFilename;
        this.size = size;
        this.contentType = contentType;
        this.stored = false;
        this.message = "校验通过：本次只做验证，文件未保存到服务器";
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public long getSize() {
        return size;
    }

    public String getContentType() {
        return contentType;
    }

    public boolean isStored() {
        return stored;
    }

    public String getMessage() {
        return message;
    }
}
