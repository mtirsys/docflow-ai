# docflow-backend 项目长期记忆

## 项目定位

- Spring Boot 4.1.1 + Java 21 + MyBatis 后端，Maven 构建。
- MySQL `docflow` 库、`documents` 表；数据库密码从环境变量 `DB_PASSWORD` 读取。
- 分层约定：`controller`（HTTP 入口）/ `service`（业务）/ `mapper`（MyBatis SQL）/ `model`（数据实体）/ `dto`（请求体）/ `exception`（统一异常处理），包名 `docflow_backend.*`。
- Day 10 起：错误响应统一为 `ErrorResponse(status, error, message, path)`；文档不存在 = Service 抛 `DocumentNotFoundException` → `GlobalExceptionHandler` 返回 404；测试用 MockMvc standalone + mockito，不依赖数据库。
- Day 18 起：删除 = 数据库记录 + 本地文件协同删。`DocumentService.delete` 采用**顺序 A（先删文件、后删 DB）**，理由：文件删除最易失败，放前面失败时 DB 未动、系统一致可重试。项目**无 @Transactional**，事务回滚不了文件删除（文件操作在事务边界外），一致性靠"顺序 + 显式失败处理"而非事务。文件失败抛 `FileStorageException`；DB 失败分 rows==0（并发删除，warn+404）与抛异常（error+兜底500，留下"记录在文件丢"由 download 404 兜底）。
- Day 24 起：`document_chunks` 表（联合唯一 document_id+chunk_index + 外键 ON DELETE CASCADE）。`DocumentService.chunkAndSave` 是项目首个 `@Transactional`（先删后插，纯 DB 操作适合事务）。

## Day 25：Spring AI 2.0 关键技术约定（向量化）

- 服务商：阿里云百炼，北京 cn-beijing，模型 `qwen3.7-text-embedding`（1024 维，OpenAI 兼容协议）。
- 依赖：`spring-ai-bom:2.0.2` + `spring-ai-starter-model-openai`（兼容 Spring Boot 4.1.1，无需加仓库）。
- **Spring AI 2.0 属性已扁平化，无 `.options.` 层级**：`spring.ai.openai.embedding.model` / `.dimensions` / `.encoding-format`（**不是** `.options.model`）。1.x 才用 `.options.`。
- **2.0 不需要覆盖 embeddings-path**：底层换用官方 openai-java SDK，base-url 带 /v1 时追加 /embeddings，拼出百炼端点 `.../compatible-mode/v1/embeddings`。1.x 的"双 v1 必 404"坑在 2.0 不存在。
- Key 走环境变量 `${DASHSCOPE_API_KEY}`、`${BAILIAN_WORKSPACE_ID}`，绝不写明文、不进 Git。
- **教训**：Spring AI 1.x→2.0 破坏性变化多，不得凭 1.x 经验套 2.0，必须抓官方文档原文核实。

## Day 28-30：向量化与 RAG 链路（关键约定）

- MySQL 是事实源、Qdrant 是派生数据：**Qdrant 丢数据后不要重新播种，直接重跑 `VectorizeDocumentIntegrationTest`**——`embedAndStore` 内部 `ensureCollection()` 幂等重建 collection（1024 维 + Cosine），`storeDocumentVectors` 先删后写幂等。
- 跑集成测试的命令行覆盖写法是 `"-Dsurefire.excludedGroups="`（属性名带 surefire. 前缀才生效；`-DexcludedGroups=` 是 Day 28 踩过的不生效写法）。SeedDocument/VectorizeDocument 两个测试头注释里仍写着旧写法，待用户同意后修正。
- Day 30 起有 ChatModel：`spring.ai.openai.chat.model=qwen3.7-plus`、`temperature=0.2`，与 embedding 复用同一百炼 base-url/api-key。ChatService 只做编排（检索→Prompt→生成→sources），检索细节全在 SearchService。

## 环境级配置（2026-11-14 起）

- **Maven 走阿里云镜像**：`C:\Users\ye\.m2\settings.xml`（mirrorOf=central → maven.aliyun.com/repository/public）。原因：本机直连 Maven Central 反复超时，DNS 解析出可疑 IP 28.0.0.120。不再需要时删该文件即恢复直连。
- @SpringBootTest 上下文冒烟测试（DocflowBackendApplicationTests）带 dummy 的 spring.ai.openai.* 测试属性（Day 25 起 classpath 有 Spring AI 后必需，否则占位符解析失败）。

## 用户偏好与约定

- 用户正在这个项目上学习 Spring Boot，基础薄弱，处于零基础阶段。
- **讲解风格要求（用户明确指定）**：大白话，不堆术语；必须出现术语时先解释再使用；多用比喻；每个概念都要结合本项目真实文件举例。
- **Day 22 用户反馈"讲得太复杂"**：之后每次回复只聚焦一个知识点，代码走读控制在 10 行以内，少用表格、少用多分支并行讲解，理解题最多 1 个且要简单。
- **不要每步出基础理解题**（Day 21 用户明确反馈嫌太简单）；关键决策点可直接确认，但不搞小测验。
- **未经用户明确同意，不要修改本项目的任何代码**（讲解任务时只读）。
