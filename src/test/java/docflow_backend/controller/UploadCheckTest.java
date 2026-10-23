package docflow_backend.controller;

import docflow_backend.exception.GlobalExceptionHandler;
import docflow_backend.mapper.DocumentMapper;
import docflow_backend.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 13 上传验证接口的自动化测试。
 *
 * 和 GlobalExceptionHandlerTest 一样用 standalone 模式：不启动应用、不连 MySQL。
 * 区别是这里装配的是"真实的 DocumentService + 伪造的 DocumentMapper"——
 * 因为被测对象就是 Service 里的校验逻辑本身，不能把 Service 也换假的。
 * 校验路径根本不碰数据库，所以 mock 掉 Mapper 即可。
 *
 * 说明：standalone 模式不走真实 Tomcat，multipart 的 max-file-size 配置不生效，
 * 所以"超过 10 MB"这个用例测的是 Service 层的第二道防线；
 * 容器层的第一道防线（MaxUploadSizeExceededException）要用 Apifox 真发请求验证。
 */
class UploadCheckTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentService documentService = new DocumentService(documentMapper);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentController(documentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("合法 PDF：返回 200，stored=false，回显文件名、大小和 Content-Type")
    void uploadValidPdf() throws Exception {
        byte[] content = "%PDF-1.4 fake content".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/api/documents/upload-check")
                        .file(new MockMultipartFile(
                                "file", "report.pdf", "application/pdf", content)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("report.pdf"))
                .andExpect(jsonPath("$.size").value(content.length))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.stored").value(false))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("大写扩展名 .MD：不区分大小写，返回 200")
    void uploadUppercaseExtension() throws Exception {
        mockMvc.perform(multipart("/api/documents/upload-check")
                        .file(new MockMultipartFile(
                                "file", "NOTES.MD", "text/markdown", "hello".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("NOTES.MD"))
                .andExpect(jsonPath("$.stored").value(false));
    }

    @Test
    @DisplayName("文件名里夹带客户端路径：只保留纯文件名回显，不透传路径")
    void stripsPathFromFilename() throws Exception {
        mockMvc.perform(multipart("/api/documents/upload-check")
                        .file(new MockMultipartFile(
                                "file", "C:\\docs\\报告.pdf", "application/pdf", "x".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("报告.pdf"));
    }

    @Test
    @DisplayName("缺少 file 字段：返回 400 并说明缺哪个字段，而不是掉进 500")
    void missingFilePart() throws Exception {
        mockMvc.perform(multipart("/api/documents/upload-check"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(
                        "缺少必需的上传字段：file（请确认使用 form-data，且字段名为 file）"));
    }

    @Test
    @DisplayName("空文件（0 字节）：返回 400")
    void emptyFile() throws Exception {
        mockMvc.perform(multipart("/api/documents/upload-check")
                        .file(new MockMultipartFile(
                                "file", "empty.pdf", "application/pdf", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("上传文件为空：请选择一个非空文件"));
    }

    @Test
    @DisplayName("不支持的扩展名 .exe：返回 400 并说明允许的类型")
    void unsupportedExtension() throws Exception {
        mockMvc.perform(multipart("/api/documents/upload-check")
                        .file(new MockMultipartFile(
                                "file", "virus.exe", "application/octet-stream", "x".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不支持的文件类型：只允许 PDF、DOCX、MD，收到的是 .exe"));
    }

    @Test
    @DisplayName("没有扩展名的文件：返回 400（按'不支持的类型'处理）")
    void noExtension() throws Exception {
        mockMvc.perform(multipart("/api/documents/upload-check")
                        .file(new MockMultipartFile(
                                "file", "README", "text/plain", "x".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("超过 10 MB：Service 层防线拦截，返回 400")
    void oversizedFile() throws Exception {
        byte[] huge = new byte[11 * 1024 * 1024]; // 11 MB，故意超过上限

        mockMvc.perform(multipart("/api/documents/upload-check")
                        .file(new MockMultipartFile(
                                "file", "big.pdf", "application/pdf", huge)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件过大：单个文件不能超过 10 MB"));
    }
}
