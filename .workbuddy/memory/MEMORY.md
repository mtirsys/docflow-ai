# docflow-backend 项目长期记忆

## 项目定位

- Spring Boot 4.1.1 + Java 21 + MyBatis 后端，Maven 构建。
- MySQL `docflow` 库、`documents` 表；数据库密码从环境变量 `DB_PASSWORD` 读取。
- 分层约定：`controller`（HTTP 入口）/ `mapper`（MyBatis SQL）/ `model`（数据实体），包名 `docflow_backend.*`。

## 用户偏好与约定

- 用户正在这个项目上学习 Spring Boot，基础薄弱，处于零基础阶段。
- **讲解风格要求（用户明确指定）**：大白话，不堆术语；必须出现术语时先解释再使用；多用比喻；每个概念都要结合本项目真实文件举例。
- **未经用户明确同意，不要修改本项目的任何代码**（讲解任务时只读）。
