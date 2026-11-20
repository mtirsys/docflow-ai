package docflow_backend.service;

import docflow_backend.dto.EmbeddingBatchResult;
import docflow_backend.exception.DocumentNotFoundException;
import docflow_backend.mapper.DocumentChunkMapper;
import docflow_backend.mapper.DocumentMapper;
import docflow_backend.model.Document;
import docflow_backend.model.DocumentChunk;
import docflow_backend.storage.LocalFileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Day 27：batchEmbed（批量向量化）的单元测试。
 *
 * 关键约定（与 EmbeddingServiceTest 一致）：embeddingService 用 Mockito mock 掉，
 * 不联网、不花 API 额度。本测试验证的是 batchEmbed 的编排逻辑——
 * 查块、逐条调 embed、成功/失败计数、失败不中断、结果打包——而不是
 * "百炼是否真的能返回 1024 维向量"（那属于集成测试，见 requests.http / 手动验证）。
 *
 * 装配取舍：
 * - documentMapper、documentChunkMapper、embeddingService 全部 mock：
 *   被测的是 DocumentService.batchEmbed 的编排，不是 SQL 也不是真实 API；
 * - 其余依赖（文件存储、解析器、切分器）在本测试完全用不到，传真对象或
 *   mock 均可，这里为简洁统一 mock 掉需要传的、真对象传不需要网络/磁盘的。
 *
 * 覆盖场景（对应 Day 27 第五步的测试清单）：
 * 1. 文档存在且有多个 Chunk → 全部成功，success = total，failed = 0；
 * 2. 文档不存在 → findById 抛 DocumentNotFoundException（→404）；
 * 3. 文档没有 Chunk → total=0 的"全零"回执，绝不调 embed；
 * 4. 部分 Chunk 失败 → 失败的计入 failures、成功的照常，总数守恒；
 * 5. 重复执行 → 每次独立统计，不写库、不产生重复数据（verify 无写操作）；
 * 6. API 调用失败（某块 embed 抛异常）→ 该块记为失败，异常被捕获不向上冒。
 */
class EmbeddingBatchServiceTest {

    private DocumentMapper documentMapper;
    private DocumentChunkMapper documentChunkMapper;
    private EmbeddingService embeddingService;
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        documentChunkMapper = mock(DocumentChunkMapper.class);
        embeddingService = mock(EmbeddingService.class);
        documentService = new DocumentService(
                documentMapper, documentChunkMapper,
                mock(LocalFileStorageService.class),
                new docflow_backend.parser.PdfTextExtractor(),
                new docflow_backend.parser.DocxTextExtractor(),
                new docflow_backend.parser.TextContentCleaner(),
                new docflow_backend.parser.TextChunker(),
                embeddingService);
    }

    /** 造一个指向已存在文档的 Document，让 findById 不抛异常。 */
    private void seedDocumentExists(long id) {
        Document doc = new Document();
        doc.setId(id);
        doc.setTitle("doc");
        doc.setFileType("MD");
        when(documentMapper.findById(id)).thenReturn(doc);
    }

    /** 造一个 chunk 实体（只有 content 和 chunkIndex 会被 batchEmbed 用到）。 */
    private DocumentChunk chunk(int index, String content) {
        DocumentChunk c = new DocumentChunk();
        c.setDocumentId(1L);
        c.setChunkIndex(index);
        c.setContent(content);
        c.setCharCount(content.length());
        return c;
    }

    /** 造一个 1024 维的假向量（维度与配置一致，值无意义，只用 size 断言维度）。 */
    private List<Float> fakeVector() {
        Float[] v = new Float[1024];
        java.util.Arrays.fill(v, 0.5f);
        return List.of(v);
    }

    @Test
    @DisplayName("多块文档全部成功：success == total，failed == 0，维度 1024，逐条调用了 embed")
    void multipleChunksAllSucceed() {
        long id = 1L;
        seedDocumentExists(id);
        List<DocumentChunk> chunks = List.of(
                chunk(0, "第一块"),
                chunk(1, "第二块"),
                chunk(2, "第三块"));
        when(documentChunkMapper.findByDocumentId(id)).thenReturn(chunks);
        when(embeddingService.embed(anyString())).thenReturn(fakeVector());

        EmbeddingBatchResult result = documentService.batchEmbed(id);

        assertEquals(id, result.documentId());
        assertEquals(3, result.totalChunks());
        assertEquals(3, result.successCount());
        assertEquals(0, result.failedCount());
        assertEquals(1024, result.dimensions());
        assertTrue(result.failures().isEmpty());

        // 逐条处理：每一块的内容都被原样传给 embed，且顺序正确
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(embeddingService, org.mockito.Mockito.times(3)).embed(captor.capture());
        assertEquals(List.of("第一块", "第二块", "第三块"), captor.getAllValues());
    }

    @Test
    @DisplayName("文档不存在：findById 抛 DocumentNotFoundException，且不查块、不调 embed")
    void missingDocumentThrows404AndDoesNothing() {
        long id = 999L;
        when(documentMapper.findById(id)).thenReturn(null);

        assertThrows(DocumentNotFoundException.class, () -> documentService.batchEmbed(id));

        verify(documentChunkMapper, never()).findByDocumentId(id);
        verify(embeddingService, never()).embed(anyString());
    }

    @Test
    @DisplayName("文档没有块：返回全零回执，绝不调 embed")
    void documentWithoutChunksReturnsZeroReceipt() {
        long id = 2L;
        seedDocumentExists(id);
        when(documentChunkMapper.findByDocumentId(id)).thenReturn(List.of());

        EmbeddingBatchResult result = documentService.batchEmbed(id);

        assertEquals(0, result.totalChunks());
        assertEquals(0, result.successCount());
        assertEquals(0, result.failedCount());
        assertEquals(0, result.dimensions());
        assertTrue(result.failures().isEmpty());
        verify(embeddingService, never()).embed(anyString());
    }

    @Test
    @DisplayName("部分块失败：失败块记入 failures，成功块照常，总数守恒且不中断")
    void partialFailureContinuesAndCountsBoth() {
        long id = 3L;
        seedDocumentExists(id);
        List<DocumentChunk> chunks = List.of(
                chunk(0, "正常块A"),
                chunk(1, "会失败块"),
                chunk(2, "正常块C"));
        when(documentChunkMapper.findByDocumentId(id)).thenReturn(chunks);
        // 用 thenAnswer 按内容分流：只有"会失败块"抛异常，其余正常返回。
        // （不用两个 when().thenReturn() 叠加，是因为 anyString() 和 eq() 两种
        //  matcher 混用会触发 Mockito 的 UnfinishedStubbing 校验。）
        when(embeddingService.embed(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if ("会失败块".equals(text)) {
                throw new RuntimeException("模拟：这块调用失败");
            }
            return fakeVector();
        });

        EmbeddingBatchResult result = documentService.batchEmbed(id);

        assertEquals(3, result.totalChunks());
        assertEquals(2, result.successCount());
        assertEquals(1, result.failedCount());
        assertEquals(1024, result.dimensions());

        // 失败明细只有 1 条，且指向 index=1 的那块
        assertEquals(1, result.failures().size());
        EmbeddingBatchResult.ChunkEmbeddingFailure f = result.failures().get(0);
        assertEquals(1, f.chunkIndex());
        assertTrue(f.reason().contains("模拟"));
    }

    @Test
    @DisplayName("API 整体失败：某块 embed 抛异常被捕获记为失败，异常不向上冒泡")
    void apiFailureIsCapturedAsFailureNotPropagated() {
        long id = 4L;
        seedDocumentExists(id);
        List<DocumentChunk> chunks = List.of(
                chunk(0, "唯一一块"),
                chunk(1, "第二块"));
        when(documentChunkMapper.findByDocumentId(id)).thenReturn(chunks);
        // 所有 embed 都抛异常：模拟 API 完全不可用
        when(embeddingService.embed(anyString()))
                .thenThrow(new RuntimeException("百炼 API 不可用"));

        // 关键断言：不抛异常，而是正常返回"全失败"回执
        EmbeddingBatchResult result = documentService.batchEmbed(id);

        assertEquals(2, result.totalChunks());
        assertEquals(0, result.successCount());
        assertEquals(2, result.failedCount());
        assertEquals(0, result.dimensions()); // 一块都没成，维度无从谈起
        assertEquals(2, result.failures().size());
    }

    @Test
    @DisplayName("重复执行：两次独立统计、结果一致，且不写数据库（无 insert/delete 调用）")
    void repeatedExecutionIsIndependentAndWritesNothing() {
        long id = 5L;
        seedDocumentExists(id);
        List<DocumentChunk> chunks = List.of(chunk(0, "一块内容"));
        when(documentChunkMapper.findByDocumentId(id)).thenReturn(chunks);
        when(embeddingService.embed(anyString())).thenReturn(fakeVector());

        EmbeddingBatchResult first = documentService.batchEmbed(id);
        EmbeddingBatchResult second = documentService.batchEmbed(id);

        assertEquals(first.successCount(), second.successCount());
        assertEquals(first.totalChunks(), second.totalChunks());

        // 不落库：batchEmbed 全程不调用任何写方法（insertBatch / deleteByDocumentId）
        verify(documentChunkMapper, never()).insertBatch(org.mockito.ArgumentMatchers.anyList());
        verify(documentChunkMapper, never()).deleteByDocumentId(id);
    }
}
