package docflow_backend.service;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;

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
     * 单条文本 → 向量（List&lt;Float&gt;，维度由配置 spring.ai.openai.embedding.dimensions=1024 决定）。
     *
     * 空文本（null / 空白）进门就拦：空文本没有语义，向量无从谈起。
     * 注意：Spring AI 的 embed(String) 只拦 null 不拦空白（空白会真的发给 API），
     * 所以这层校验必须自己写——不能指望框架替你把所有脏数据都挡住。
     *
     * 返回 List&lt;Float&gt; 而非 float[]：EmbeddingModel 原生给的是 float[]，
     * 这里做一次"装箱 + 收进 List"的转换，对外交付更常用的集合类型
     * （调用方遍历、存取、交给 JSON 序列化都更顺）。
     */
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文本不能为空：空文本没有语义，无法生成向量");
        }
        float[] raw = embeddingModel.embed(text);
        return toFloatList(raw);
    }

    /**
     * float[] → List&lt;Float&gt; 的装箱转换。
     * 逐一把基本类型 float 包装成对象 Float，再收进不可变 List。
     * 拆成单独方法：让 embed() 的主干只表达"校验 → 调模型 → 转类型"三步，
     * 装箱这种机械动作不喧宾夺主。
     */
    private static List<Float> toFloatList(float[] raw) {
        if (raw == null) {
            // 模型理论上不该返回 null，但防御一下：与其让调用方 NPE，
            // 不如在这里明确抛错，把"哪里坏了"说清楚。
            throw new IllegalStateException("Embedding 返回了 null，模型调用异常");
        }
        Float[] boxed = new Float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            boxed[i] = raw[i]; // 自动装箱：float 自动转 Float
        }
        return List.of(boxed);
    }
}
