-- =====================================================================
-- document_chunks 表 DDL（Day 24 设计，2026-11-21 重建并首次入库版本控制）
-- =====================================================================
-- 背景：这张表 Day 24 时是手工建的、没有 SQL 脚本进版本库，数据库重建后丢失。
-- 现按实体类 DocumentChunk 与 DocumentChunkMapper 的真实用法反推补齐 DDL，
-- 并以此文件作为表结构的唯一事实源，今后改表先改这里。
--
-- 字段与列的对应关系（三处必须一致，改一处查另两处）：
--   实体           docflow_backend.model.DocumentChunk
--   INSERT 列     DocumentChunkMapper.insertBatch（document_id/chunk_index/content/char_count）
--   查询用法      findByDocumentId（WHERE document_id ORDER BY chunk_index）
--
-- 设计要点（不是拍脑袋，各自有出处）：
-- 1. id 自增主键：同时是 Qdrant Point ID 的来源（Day 28 约定 Point ID = chunk.id，
--    天然一对一 + upsert 幂等）；
-- 2. UNIQUE (document_id, chunk_index)：chunkAndSave"先删后插"的联合唯一双保险，
--    保证任何时刻一篇文档最多只有一套块（见 DocumentService.chunkAndSave 注释）；
--    它的最左前缀 (document_id) 同时服务于 findByDocumentId 查询和外键，无需再建单列索引；
-- 3. 外键 fk_chunk_document：块的归属必须是真实存在的文档（见 DocumentChunk 注释）；
-- 4. content 用 TEXT：TextChunker 保证每块 <= 500 字符（chunkSize=500/overlap=50），
--    TEXT 上限 64KB，绰绰有余；
-- 5. created_at：Java 不填，数据库 DEFAULT CURRENT_TIMESTAMP 自动生成。
-- =====================================================================

CREATE TABLE document_chunks (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    document_id BIGINT       NOT NULL,
    chunk_index INT          NOT NULL,
    content     TEXT         NOT NULL,
    char_count  INT          NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_chunk (document_id, chunk_index),
    CONSTRAINT fk_chunk_document
        FOREIGN KEY (document_id) REFERENCES documents (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '文档分块表：RAG 检索的备料';

-- 建表后自检（应返回 0）：
-- SELECT COUNT(*) FROM document_chunks;
