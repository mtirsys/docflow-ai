# docflow-backend 项目长期记忆

## 项目定位

- Spring Boot 4.1.1 + Java 21 + MyBatis 后端，Maven 构建。
- MySQL `docflow` 库、`documents` 表；数据库密码从环境变量 `DB_PASSWORD` 读取。
- 分层约定：`controller`（HTTP 入口）/ `service`（业务）/ `mapper`（MyBatis SQL）/ `model`（数据实体）/ `dto`（请求体）/ `exception`（统一异常处理），包名 `docflow_backend.*`。
- Day 10 起：错误响应统一为 `ErrorResponse(status, error, message, path)`；文档不存在 = Service 抛 `DocumentNotFoundException` → `GlobalExceptionHandler` 返回 404；测试用 MockMvc standalone + mockito，不依赖数据库。
- Day 18 起：删除 = 数据库记录 + 本地文件协同删。`DocumentService.delete` 采用**顺序 A（先删文件、后删 DB）**，理由：文件删除最易失败，放前面失败时 DB 未动、系统一致可重试。项目**无 @Transactional**，事务回滚不了文件删除（文件操作在事务边界外），一致性靠"顺序 + 显式失败处理"而非事务。文件失败抛 `FileStorageException`；DB 失败分 rows==0（并发删除，warn+404）与抛异常（error+兜底500，留下"记录在文件丢"由 download 404 兜底）。

## 用户偏好与约定

- 用户正在这个项目上学习 Spring Boot，基础薄弱，处于零基础阶段。
- **讲解风格要求（用户明确指定）**：大白话，不堆术语；必须出现术语时先解释再使用；多用比喻；每个概念都要结合本项目真实文件举例。
- **Day 22 用户反馈"讲得太复杂"**：之后每次回复只聚焦一个知识点，代码走读控制在 10 行以内，少用表格、少用多分支并行讲解，理解题最多 1 个且要简单。
- **不要每步出基础理解题**（Day 21 用户明确反馈嫌太简单）；关键决策点可直接确认，但不搞小测验。
- **未经用户明确同意，不要修改本项目的任何代码**（讲解任务时只读）。
