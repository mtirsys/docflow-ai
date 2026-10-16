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
- 文档列表查询
- 单条文档查询
- 文档修改
- 文档删除
- 参数校验（title 不能为空）
- 统一异常处理（400 / 404 / 500 返回 JSON）

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