package docflow_backend.storage;

import docflow_backend.exception.FileStorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day 15：LocalFileStorageService 的单元测试。
 *
 * @TempDir 是 JUnit 发的"临时测试房间"：每个测试方法拿到一个真实存在的新目录，
 * 测试结束自动清理。所以这里可以真实写文件、真实读回比对，
 * 既验证了磁盘行为，又不会碰 D:/docflow-storage 里的正式文件。
 */
class LocalFileStorageServiceTest {

    @TempDir
    Path tempDir;

    private static MockMultipartFile pdf(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/pdf", content);
    }

    @Test
    @DisplayName("存储成功：UUID 存储名 + 小写扩展名，内容与原文件一致，不含用户文件名")
    void storeWritesFileWithGeneratedKey() throws IOException {
        LocalFileStorageService service = new LocalFileStorageService(tempDir.toString());
        byte[] content = "%PDF-1.4 fake content".getBytes(StandardCharsets.UTF_8);

        String key = service.store(pdf("我的报告.PDF", content));

        assertTrue(key.matches("[0-9a-f-]{36}\\.pdf"), "存储名应是 UUID + .pdf，实际：" + key);
        assertFalse(key.contains("我的报告"), "存储名不能包含用户上传的文件名");

        Path stored = tempDir.resolve(key);
        assertTrue(Files.exists(stored), "文件应真实写入存储目录");
        // 只比对字节是否一致，不打印文件内容
        assertArrayEquals(content, Files.readAllBytes(stored));
    }

    @Test
    @DisplayName("同一文件上传两次：生成不同存储名，两份都保留、互不覆盖")
    void storeTwiceGeneratesDistinctKeys() {
        LocalFileStorageService service = new LocalFileStorageService(tempDir.toString());

        String first = service.store(pdf("a.pdf", "one".getBytes()));
        String second = service.store(pdf("a.pdf", "two".getBytes()));

        assertNotEquals(first, second);
        assertTrue(Files.exists(tempDir.resolve(first)));
        assertTrue(Files.exists(tempDir.resolve(second)));
    }

    @Test
    @DisplayName("存储目录还不存在：自动创建多级目录，无需手工准备")
    void storeCreatesMissingDirectory() {
        Path nested = tempDir.resolve("level1/level2/storage");
        LocalFileStorageService service = new LocalFileStorageService(nested.toString());

        String key = service.store(pdf("a.pdf", "x".getBytes()));

        assertTrue(Files.exists(nested.resolve(key)));
    }

    @Test
    @DisplayName("存储目录被同名普通文件占用：创建失败，抛 FileStorageException")
    void storeFailureThrowsFileStorageException() throws IOException {
        // 制造一个"想建目录但同名文件已存在"的必然失败场景
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "我是一个普通文件，不是目录");
        LocalFileStorageService service = new LocalFileStorageService(blocker.toString());

        assertThrows(FileStorageException.class,
                () -> service.store(pdf("a.pdf", "x".getBytes())));
    }
}
