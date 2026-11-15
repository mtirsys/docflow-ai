package docflow_backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文冒烟测试：整个 Spring 容器能不能装配起来。
 *
 * Day 25 起 classpath 上有 Spring AI starter，自动配置会创建 EmbeddingModel——
 * 它要读 spring.ai.openai.* 属性。生产环境这些值来自环境变量（fail-fast：
 * 没设 Key 启动就报错，这是故意的）。但单元测试不该依赖"开发者本机设了 Key"，
 * 所以这里给 dummy 测试属性：bean 能创建即可。EmbeddingModel 是懒连接的，
 * 只要没人真的调 embed()，就绝不会往 dummy 地址发请求。
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=dummy-key-for-context-test",
        "spring.ai.openai.base-url=https://dummy.example.com/compatible-mode/v1"
})
class DocflowBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
