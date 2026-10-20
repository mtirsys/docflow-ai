package docflow_backend.exception;

import docflow_backend.controller.DocumentController;
import docflow_backend.dto.DocumentPageResponse;
import docflow_backend.model.Document;
import docflow_backend.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 10 统一异常处理的自动化验证。
 *
 * 不启动真实应用、不连 MySQL：用 MockMvc 的"standalone"模式，
 * 只装配 DocumentController + GlobalExceptionHandler + 一个 Mockito 伪造的 DocumentService，
 * 所以这组测试验证的是"HTTP 层对各种异常的翻译是否正确"，与数据库无关。
 */
class GlobalExceptionHandlerTest {

    private DocumentService documentService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);

        // 显式提供校验器，让 @Valid + @NotBlank 在 standalone 模式下也生效
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentController(documentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("正常新增请求：仍返回 201 和原有格式的成功响应")
    void createValidDocument() throws Exception {
        given(documentService.create(any(Document.class))).willReturn(1L);

        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Java 学习笔记\",\"fileType\":\"PDF\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("created"))
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("标题为空的新增请求：返回 400 + 统一错误格式")
    void createWithBlankTitle() throws Exception {
        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \",\"fileType\":\"PDF\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("文档标题不能为空"))
                .andExpect(jsonPath("$.path").value("/api/documents"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("非法 JSON 请求：返回 400 而不是 500")
    void createWithMalformedJson() throws Exception {
        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/api/documents"));
    }

    @Test
    @DisplayName("路径参数不是数字：返回 400，提示参数格式错误")
    void findByIdWithNonNumericId() throws Exception {
        mockMvc.perform(get("/api/documents/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("id")))
                .andExpect(jsonPath("$.path").value("/api/documents/abc"));
    }

    @Test
    @DisplayName("查询不存在的文档：返回 404 + 统一错误格式")
    void findByIdNotExists() throws Exception {
        given(documentService.findById(999L))
                .willThrow(new DocumentNotFoundException(999L));

        mockMvc.perform(get("/api/documents/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("文档不存在：id=999"))
                .andExpect(jsonPath("$.path").value("/api/documents/999"));
    }

    @Test
    @DisplayName("默认分页参数：返回分页元数据")
    void findAllUsesDefaultPagination() throws Exception {
        given(documentService.findPage(0, 10))
                .willReturn(new DocumentPageResponse(java.util.List.of(), 0, 10, 0, 0));

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    @DisplayName("非法分页参数：Service 的范围校验统一返回 400")
    void findAllWithInvalidPaginationReturnsBadRequest() throws Exception {
        willThrow(new IllegalArgumentException("page 不能小于 0"))
                .given(documentService).findPage(-1, 10);
        willThrow(new IllegalArgumentException("size 必须在 1 到 100 之间"))
                .given(documentService).findPage(0, 0);
        willThrow(new IllegalArgumentException("size 必须在 1 到 100 之间"))
                .given(documentService).findPage(0, 101);

        mockMvc.perform(get("/api/documents?page=-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page 不能小于 0"));
        mockMvc.perform(get("/api/documents?size=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size 必须在 1 到 100 之间"));
        mockMvc.perform(get("/api/documents?size=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size 必须在 1 到 100 之间"));
    }

    @Test
    @DisplayName("删除接口抛未预期异常：500 + 通用提示，响应体不含 SQL/密码/异常消息")
    void deleteTriggersUnexpectedException() throws Exception {
        willThrow(new RuntimeException("boom: SELECT * FROM documents WHERE pwd='secret123'"))
                .given(documentService).delete(eq(1L));

        String body = mockMvc.perform(delete("/api/documents/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后重试"))
                .andExpect(jsonPath("$.path").value("/api/documents/1"))
                .andReturn().getResponse().getContentAsString();

        // 明确断言：内部异常的原文、SQL 片段、密码都不允许出现在响应里
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("boom"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("SELECT"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("secret123"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("RuntimeException"));
    }
}
