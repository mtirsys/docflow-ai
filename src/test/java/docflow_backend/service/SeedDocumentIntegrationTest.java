package docflow_backend.service;

import docflow_backend.dto.DocumentChunkResponse;
import docflow_backend.dto.UploadResponse;
import docflow_backend.mapper.DocumentChunkMapper;
import docflow_backend.model.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day 28 补课（2026-11-21）：真实链路造数据——上传一份真实 Markdown 文档并分块落库。
 *
 * 背景：Day 28 验收发现运行态数据链路从未打通——MySQL 的 document_chunks 表
 * 在数据库重建时丢了（本文件写就当天已按 src/main/resources/sql/document_chunks.sql
 * 重建），Qdrant 生产 Collection 也没有任何真实 Point。本类负责真实链路的前两步：
 *   upload（校验→落盘→元数据入库）→ chunkAndSave（提取→清洗→分块→落库）。
 * 第三步 embedAndStore 推 Qdrant 属于后续工作，不在本类。
 *
 * 为什么不能直接用文档 id=3 造块（读代码后的结论，不是猜的）：
 * id=3 是早期通过 JSON 接口 create() 出来的"纯元数据记录"——status=PENDING、
 * storage_key=NULL，盘上根本没有文件。chunkAndSave 的第 2 步
 * extractCleanedContent 会按 file_type=PDF 走 locateExistingFile(storageKey)，
 * 文件不存在直接失败。真实链路必须从"上传一个真实文件"开始，新文档会拿到
 * 新的自增 id（大概率是 4），之后 embedAndStore 也用这个新 id，不是 3。
 *
 * 为什么用 @SpringBootTest 直接注入 Service，而不是起服务调 HTTP：
 * Controller 只是"接收请求 + 调业务 + 包装响应"的薄壳（见 DocumentController），
 * 直接调 Service 与走 HTTP 殊途同归、覆盖同一批业务代码；且本机 HTTP 代理
 * 会劫持 127.0.0.1 的请求（Day 28 踩坑记录），不起端口反而更稳。
 *
 * 重复执行的后果：每次运行都新上传一份文档（新 id、新文件、新的一套块），
 * 不影响旧数据，但会累积。跑一次就够。
 *
 * 运行前提：MySQL 已启动，且 document_chunks 表已按
 * src/main/resources/sql/document_chunks.sql 建好。
 *
 * 运行方式（在 docflow-backend 目录下；-DexcludedGroups= 用来临时放开
 * pom 里"默认排除 integration 组"的配置，跑完即恢复，不改动 pom）：
 *   mvn.cmd test -Dtest=SeedDocumentIntegrationTest -DfailIfNoTests=false "-DexcludedGroups="
 */
@Tag("integration")
@SpringBootTest
class SeedDocumentIntegrationTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentChunkMapper documentChunkMapper;

    @Test
    @DisplayName("上传真实 Markdown 文档 → 分块落库 → 回读验证（chunkId 即将来的 Qdrant Point ID）")
    void uploadAndChunkRealDocument() {
        // 1. 构造一份有真实语义的 Markdown。八段内容主题各不相同（项目定位/解析/
        //    分块/向量化/Qdrant/检索/幂等/余弦），既贴近本项目，也为 Day 29 的
        //    语义检索准备"能被不同问题命中的不同块"。
        String content = """
                # DocFlow AI 架构笔记

                DocFlow AI 是一个文档智能检索系统。它把用户上传的文档（PDF、Word、Markdown）\
                解析成纯文本，切成小块，为每一块计算语义向量，存入向量数据库 Qdrant。\
                用户提问时，系统把问题也转成向量，在向量库里找到语义最相近的文本块，\
                作为回答问题的素材。

                ## 文档解析流水线

                上传的文件首先经过解析器提取纯文本。PDF 由 Apache PDFBox 解析，\
                Word 文档由 Apache POI 解析，Markdown 直接按 UTF-8 读取。\
                提取的文本再经过清洗器统一换行符、折叠空行、删除控制字符，\
                保证后续处理拿到的是干净的文本。

                ## 分块策略

                清洗后的文本按段落聚合切分成块。每一块默认最多五百个字符，\
                相邻块之间保留五十个字符的重叠，避免关键句子恰好被切断而丢失上下文。\
                每个块都会写入 MySQL 的 document_chunks 表，获得一个自增主键 id，\
                这个 id 同时也是它在 Qdrant 里的 Point ID。

                ## Embedding 向量化

                阿里云百炼提供的文本嵌入模型把每块文本转换成一千零二十四维的语义向量。\
                语义相近的文本，其向量在空间中的方向也相近，夹角更小。\
                查询时用户的问题也被转换成同维度的向量，才能与文档向量进行比较。

                ## Qdrant 向量数据库

                Qdrant 是专门的向量数据库。本项目使用余弦相似度作为距离度量，\
                关注向量方向而非长度。Collection 建好后维度不可更改，\
                必须与嵌入模型输出维度保持一致。每个 Point 的 payload 只存\
                documentId 和 chunkId，原文始终以 MySQL 为事实源。

                ## 检索与 RAG 展望

                检索流程是：问题转向量，在 Qdrant 中找最相似的 Top K 个块，\
                拿 chunkId 回 MySQL 取回原文，最终把这些原文交给大语言模型生成回答。\
                这就是检索增强生成（RAG）的基本思路。

                ## 幂等与一致性

                向量化写入采用先删后写策略：按 documentId 删除旧向量再整批写入新向量，\
                重复执行不会产生重复数据。Qdrant 的 upsert 语义保证相同 Point ID\
                的写入是覆盖而不是追加，这两层机制共同保证数据一致。

                ## 距离度量：余弦相似度

                余弦相似度衡量两个向量方向的接近程度，取值范围是负一到正一，\
                越接近正一表示方向越一致、语义越相似。文本嵌入向量通常已经归一化，\
                此时余弦相似度等价于点积。检索时 Qdrant 返回的分数越高表示越相关。
                """;

        // 2. 真实上传。MockMultipartFile 是 spring-test 提供的内存文件，
        //    与 HTTP multipart 到达 Service 层时的形态等价，全程不起网络。
        MockMultipartFile file = new MockMultipartFile(
                "file",                                 // 表单字段名（与 Controller 的 @RequestParam("file") 对应）
                "docflow-架构笔记.md",                  // 原始文件名：upload 的白名单只认扩展名，.md 合法
                "text/markdown",                        // 客户端自报的 Content-Type（不参与校验，仅回显）
                content.getBytes(StandardCharsets.UTF_8)); // 内容按 UTF-8 编码；readText 也按 UTF-8 读

        UploadResponse uploaded = documentService.upload(file);
        assertTrue(uploaded.stored(), "文件应真实落盘");
        Long documentId = uploaded.id();
        assertNotNull(documentId, "元数据应入库并拿到自增 id");
        System.out.printf(">>> 上传完成：documentId=%d, storageKey=%s%n",
                documentId, uploaded.storageKey());

        // 3. 真实分块：提取 → 清洗 → TextChunker(500/50) → 先删后插落库。
        //    走的是 Day 24 的既有代码，本类不含任何分块逻辑。
        DocumentChunkResponse chunked = documentService.chunkAndSave(documentId);
        assertEquals(documentId, chunked.documentId());
        assertTrue(chunked.totalChunks() >= 1, "至少应切出 1 块");
        assertTrue(chunked.totalChars() > 0);

        // 4. 回读验证（眼见为实）：从 MySQL 查回来，打印每个块的
        //    chunkId —— 它就是 Day 28 写 Qdrant 时会用的 Point ID。
        List<DocumentChunk> chunks = documentChunkMapper.findByDocumentId(documentId);
        assertEquals(chunked.totalChunks(), chunks.size(), "回读块数应与回执一致");

        for (DocumentChunk chunk : chunks) {
            String preview = chunk.getContent()
                    .substring(0, Math.min(40, chunk.getContent().length()))
                    .replace('\n', ' ');
            System.out.printf("    chunkId=%d, chunkIndex=%d, charCount=%d, preview=%s...%n",
                    chunk.getId(), chunk.getChunkIndex(), chunk.getCharCount(), preview);
        }

        System.out.printf(">>> 分块完成：文档 id=%d 共 %d 块、%d 字。%n"
                        + ">>> 记住这个 id：下一步推 Qdrant 时调用 embedAndStore(%d)。%n",
                documentId, chunked.totalChunks(), chunked.totalChars(), documentId);
    }
}
