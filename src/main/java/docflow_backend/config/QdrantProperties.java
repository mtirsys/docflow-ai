package docflow_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Day 28：Qdrant 连接配置——application.properties 里 docflow.qdrant.* 前缀
 * 的属性会由 Spring Boot 自动"对号入座"填进本类的同名字段（relaxed binding：
 * 中划线命名对驼峰，如 use-tls 对 useTls）。
 *
 * 为什么不直接在 QdrantConfig 里 @Value 一条条取：
 * 1. 六个参数是一组语义完整的"连接档案"，收进一个类型里，构造器注入一次拿全，
 *    不用在多个类里散着 @Value；
 * 2. 单独成类意味着"Qdrant 连接信息长什么样"有唯一的定义处，将来加字段
 *    （如超时、mTLS）只改这里；
 * 3. 它是普通 POJO，测试里可以直接 new 出来构造客户端，不必起 Spring 容器。
 *
 * 默认值策略与 DB_PASSWORD / DASHSCOPE_API_KEY 一致：真实值住环境变量，
 * 文件里只有占位符——本地开发 localhost 直连无需任何配置。
 * Qdrant 本地单机不需要 API Key（留空即匿名），生产或 Qdrant Cloud 必须设置。
 *
 * 注意 vectorDimension：值来自 spring.ai.openai.embedding.dimensions 的引用
 * （见 application.properties），不是重新写一遍 1024——embedding 维度是因，
 * collection 维度是果，两个数字必须是同一个事实，否则写入时维度断言会拦住。
 */
@ConfigurationProperties(prefix = "docflow.qdrant")
public class QdrantProperties {

    /** Qdrant 服务器地址。本地默认 localhost，生产填内网域名或 Qdrant Cloud 地址。 */
    private String host = "localhost";

    /** gRPC 端口（Java 客户端走这里）。Qdrant 默认 6334；REST 6333 是给
     *  HTTP API / Dashboard 用的，别混淆——连错端口客户端会一直连接失败。 */
    private int port = 6334;

    /** API Key。空字符串 = 匿名连接（本地默认）；生产必须走环境变量 QDRANT_API_KEY。 */
    private String apiKey = "";

    /** Collection 名（相当于 MySQL 的"表名"）：doc_chunks 存所有文档块的向量。 */
    private String collection = "doc_chunks";

    /** 是否启用 TLS。本地 http 明文 false；Qdrant Cloud（https）必须 true。 */
    private boolean useTls = false;

    /** 向量维度。引用 spring.ai.openai.embedding.dimensions，默认 1024。 */
    private int vectorDimension = 1024;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public boolean isUseTls() {
        return useTls;
    }

    public void setUseTls(boolean useTls) {
        this.useTls = useTls;
    }

    public int getVectorDimension() {
        return vectorDimension;
    }

    public void setVectorDimension(int vectorDimension) {
        this.vectorDimension = vectorDimension;
    }
}
