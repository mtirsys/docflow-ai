package docflow_backend.service;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Day 25：文本向量化服务——只管"文本进、向量出"这一件事。
 *
 * 与 DocumentService 平级：不掺和文档业务（上传/下载/分块），
 * 将来给文本块算向量时由调用方（如 DocumentService 或向量化流程）调它，职责分明。
 *
 * EmbeddingModel 是 Spring AI starter 自动配置好的（读 application.properties
 * 的 spring.ai.openai.* ，指向百炼），本类只做两件事：
 * 1. 构造器注入拿到这个"模型客户端"；
 * 2. 进门校验：空文本没有语义，不该浪费一次注定白跑的 API 调用（也是省钱）。
 */
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 单条文本 → 向量（float[]，维度由配置 spring.ai.openai.embedding.dimensions=1024 决定）。
     *
     * 空文本（null / 空白）进门就拦：空文本没有语义，向量无从谈起。
     * 注意：Spring AI 的 embed(String) 只拦 null 不拦空白（空白会真的发给 API），
     * 所以这层校验必须自己写——不能指望框架替你把所有脏数据都挡住。
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文本不能为空：空文本没有语义，无法生成向量");
        }
        return embeddingModel.embed(text);
    }
}
