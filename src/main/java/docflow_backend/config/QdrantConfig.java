package docflow_backend.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Day 28：Qdrant 的 Java 装配类——只做一件事：把 QdrantProperties 里的
 * 连接档案变成一个可注入的 QdrantClient Bean。
 *
 * 装配结构（与 MyBatis/数据源由 starter 自动装配不同，这里手工装配，全程可见）：
 * QdrantGrpcClient（真正的 gRPC 通道：host + port + TLS + API Key）
 *   → 包进 QdrantClient（官方客户端的门面，upsert/delete/count 都从它走）。
 *
 * 关于 API Key 的判空：withApiKey 空字符串也是合法调用，但显式跳过更诚实
 * ——"没配 Key"和"配了空 Key"不该是同一种请求；也避免误把空串当鉴权凭据
 * 发给服务端比对。其余连接参数不做校验：连不上时 gRPC 会在第一次调用时
 * 以异常明说，启动期不替它猜错。
 */
@Configuration
@EnableConfigurationProperties(QdrantProperties.class)
public class QdrantConfig {

    @Bean
    public QdrantClient qdrantClient(QdrantProperties properties) {
        QdrantGrpcClient.Builder grpcBuilder =
                QdrantGrpcClient.newBuilder(properties.getHost(), properties.getPort(), properties.isUseTls());

        if (!properties.getApiKey().isBlank()) {
            grpcBuilder.withApiKey(properties.getApiKey());
        }

        return new QdrantClient(grpcBuilder.build());
    }
}
