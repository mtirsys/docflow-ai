# DocFlow AI

## 项目简介

DocFlow AI 是一个基于 Java 的 AI 知识库项目，目前处于基础后端开发阶段。
本阶段完成了一个可运行的文档管理后端服务，提供文档的增删改查接口，支持参数校验与统一异常处理。

## 技术栈

- Java 21
- Spring Boot 4.1.1
- Maven
- MySQL 8.x
- MyBatis
- spring-boot-starter-validation

## 当前功能

- 健康检查
- 项目基本信息
- 数据库连接检查
- 文档新增
- 文档分页查询（`GET /api/documents`，默认 `page=0`、`size=10`，按 id 倒序）
- 单条文档查询
- 文档修改
- 文档删除
- 参数校验（title 不能为空）
- 统一异常处理（400 / 404 / 500 返回 JSON）
- 文件上传校验（`POST /api/documents/upload-check`，只验证、不落盘）

## 文档分页查询

`GET /api/documents` 从普通数组响应变更为分页对象响应。这是接口的兼容性变更：
原来直接读取数组的客户端，需要改为读取 `content` 字段。

- `page`：可选，默认 `0`，从 0 开始计数，不能小于 0。
- `size`：可选，默认 `10`，范围为 1 到 100。
- 返回结果始终按 `id DESC` 排序，避免翻页时顺序不稳定。

示例请求：

```http
GET /api/documents?page=0&size=10
```

空表时的响应：

```json
{
  "content": [],
  "page": 0,
  "size": 10,
  "totalElements": 0,
  "totalPages": 0
}
```

## 文件上传校验

`POST /api/documents/upload-check` 只验证文件"能不能收"，**不保存文件、不写数据库**（响应里 `stored` 恒为 `false`）。

请求格式是 `multipart/form-data`，字段名必须为 `file`。校验规则：

- 文件必须非空（0 字节拒绝）
- 扩展名白名单：`pdf`、`docx`、`md`（不区分大小写）
- 单文件不超过 10 MB（multipart 配置与业务层各拦一道，双保险）

成功响应示例：

```json
{
  "originalFilename": "report.pdf",
  "size": 1048576,
  "contentType": "application/pdf",
  "stored": false,
  "message": "校验通过：本次只做验证，文件未保存到服务器"
}
```

校验失败返回 400，错误体与其他接口一致的 `ErrorResponse` 格式。对应的上层配置在 `application.properties`：

```properties
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=12MB
```

## 环境要求

- JDK 21
- MySQL 8.x
- Maven（或使用项目自带的 `mvnw`）

## 如何启动

### 1. 创建数据库和表

```sql
CREATE DATABASE docflow DEFAULT CHARACTER SET utf8mb4;

USE docflow;

CREATE TABLE documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    file_type VARCHAR(50),
    status VARCHAR(50) DEFAULT 'PENDING',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
