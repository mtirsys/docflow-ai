package docflow_backend.service;

import docflow_backend.dto.DocumentContentResponse;
import docflow_backend.dto.DocumentDownload;
import docflow_backend.dto.DocumentPageResponse;
import docflow_backend.dto.UploadCheckResponse;
import docflow_backend.dto.UploadResponse;
import docflow_backend.exception.DocumentNotFoundException;
import docflow_backend.exception.FileStorageException;
import docflow_backend.mapper.DocumentMapper;
import docflow_backend.model.Document;
import docflow_backend.storage.LocalFileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;
import java.util.List;
import java.util.Set;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** Day 13：允许上传的扩展名白名单，全部转成小写后再比较，所以 .PDF/.Pdf 也放行。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "md");

    /** Day 13：单文件上限 10 MB（10 * 1024 * 1024 字节）。 */
    private static final long MAX_UPLOAD_SIZE_BYTES = 10L * 1024 * 1024;

    private final DocumentMapper documentMapper;
    private final LocalFileStorageService fileStorageService;

    public DocumentService(DocumentMapper documentMapper,
                           LocalFileStorageService fileStorageService) {
        this.documentMapper = documentMapper;
        this.fileStorageService = fileStorageService;
    }

    public Long create(Document document) {
        int rows = documentMapper.insert(document);

        if (rows != 1 || document.getId() == null) {
            throw new IllegalStateException("新增文档失败");
        }

        return document.getId();
    }

    public List<Document> findAll() {
        return documentMapper.findAll();
    }

    /**
     * 校验分页边界后，再把 0 开始的页码换算成数据库需要跳过的记录数。
     */
    public DocumentPageResponse findPage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page 不能小于 0");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size 必须在 1 到 100 之间");
        }

        long offset = (long) page * size;
        List<Document> content = documentMapper.findPage(size, offset);
        long totalElements = documentMapper.count();
        long totalPages = totalElements / size
                + (totalElements % size == 0 ? 0 : 1);

        return new DocumentPageResponse(content, page, size, totalElements, totalPages);
    }

    /**
     * 查不到不再是"返回 null 让调用者猜"，而是直接声明事实：这条数据不存在。
     * 抛出的异常由 GlobalExceptionHandler 统一翻译成 404 响应。
     */
    public Document findById(Long id) {
        Document document = documentMapper.findById(id);
        if (document == null) {
            throw new DocumentNotFoundException(id);
        }
        return document;
    }

    /**
     * update/delete 以前靠 boolean 返回值让 Controller 判断 404，
     * 现在同样改为：影响行数为 0 就说明目标不存在，直接抛异常。
     */
    public void update(Long id, Document document) {
        String fileType = document.getFileType() == null
                ? "" : document.getFileType().trim();

        int rows = documentMapper.updateById(
                id,
                document.getTitle().trim(),
                fileType
        );

        if (rows == 0) {
            throw new DocumentNotFoundException(id);
        }
    }

    /**
     * Day 18：删除文档 = 协同删除"数据库记录 + 本地文件"。
     *
     * 顺序选择：先删文件、再删 DB（顺序 A）。
     * 文件删除最易失败（权限/IO），放在 DB 删除之前：一旦失败，DB 还没被动过，
     * 系统保持完全一致，客户端可安全重试。反过来（先 DB 后文件）若文件删失败，
     * 会留下查不到 storageKey 的孤儿文件，无法补救。
     *
     * 注意：@Transactional 只能回滚 SQL，回滚不了文件删除（文件操作在事务边界之外），
     * 所以这里不依赖事务，靠"顺序 + 失败时显式处理"保证一致。
     */
    public void delete(Long id) {
        // 1. 先查记录：不存在则 findById 抛 DocumentNotFoundException（→404）；
        //    存在则拿到 storageKey。它必须在删 DB 之前缓存——DB 删完就查不到了。
        Document document = findById(id);
        String storageKey = document.getStorageKey();

        // 2. 先删本地文件。delete 返回 false 仅指"真没删成"（IO 异常或路径越界）；
        //    文件本来就不存在会返回 true，所以"记录在、文件已丢"也能正常继续删 DB。
        boolean fileDeleted = fileStorageService.delete(storageKey);
        if (!fileDeleted) {
            log.error("删除文档时文件删除失败，已中止数据库删除：id={}, storageKey={}", id, storageKey);
            throw new FileStorageException("文件删除失败");
        }

        // 3. 再删数据库记录。两种失败分开处理：
        //    - 抛异常（连接断等）：文件已删、记录还在 → "记录在、文件丢"，
        //      download 的 FileNotFoundException→404 会兜底；这里记 error 让运维可见。
        //    - 返回 0：findById 时还在、deleteById 却没删到 → 期间被并发删除，
        //      文件已被我们删、记录也没了，状态其实一致，按既有契约返回 404。
        try {
            int rows = documentMapper.deleteById(id);
            if (rows == 0) {
                log.warn("文件已删除，但数据库记录已不存在（疑似并发删除）：id={}, storageKey={}",
                        id, storageKey);
                throw new DocumentNotFoundException(id);
            }
        } catch (DocumentNotFoundException e) {
            throw e; // 404，不进下面的 500 通道
        } catch (RuntimeException e) {
            log.error("文件已删除但数据库删除抛异常，已产生不一致（记录在、文件丢）：id={}, storageKey={}",
                    id, storageKey, e);
            throw e; // → 兜底 500
        }
    }

    /**
     * Day 13：只验证上传文件"能不能收"，不保存、不写数据库。
     *
     * 校验失败的口径统一为 IllegalArgumentException，
     * 这样 GlobalExceptionHandler 里现成的"参数非法 → 400"翻译就能直接复用，
     * 不需要为上传再发明一套新的错误机制。
     *
     * 注意：这里没有把文件内容读进内存，也没有把文件名拼成任何路径——
     * 只看元数据（文件名、大小），这正是"验证接口"和"存储接口"的分界线。
     */
    public UploadCheckResponse checkUploadedFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空：请选择一个非空文件");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("缺少文件名：上传的文件没有名字");
        }

        // 旧版浏览器可能在文件名里夹带客户端路径（如 C:\docs\a.pdf），
        // 先剥掉斜杠前的部分，只用纯粹的文件名做判断和回显，绝不直接拼路径。
        String filename = extractFilename(originalName);

        String extension = extractExtension(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "不支持的文件类型：只允许 PDF、DOCX、MD，收到的是 ." + extension);
        }

        // 双保险：multipart 配置会在更外层拦截超大请求，
        // 但为了不依赖配置，业务层自己也卡一道 10 MB 的线。
        if (file.getSize() > MAX_UPLOAD_SIZE_BYTES) {
            throw new IllegalArgumentException("文件过大：单个文件不能超过 10 MB");
        }

        // getSize() 是服务器解析报文后数出来的字节数；getContentType() 是客户端自报的，
        // 两者可信度不同，所以响应里分开返回，让调用方自己判断。
        return new UploadCheckResponse(filename, file.getSize(), file.getContentType());
    }

    /**
     * 上传的业务编排放在 Service：校验、落盘、元数据入库都属于同一次上传。
     * MySQL 事务无法回滚普通磁盘写入，所以数据库失败时要主动删除刚刚写入的文件。
     */
    public UploadResponse upload(MultipartFile file) {
        UploadCheckResponse checked = checkUploadedFile(file);
        String storageKey = fileStorageService.store(file);

        Document document = new Document();
        document.setTitle(checked.getOriginalFilename());
        document.setOriginalFilename(checked.getOriginalFilename());
        document.setFileType(extractExtension(checked.getOriginalFilename()).toUpperCase());
        document.setFileSize(checked.getSize());
        document.setStorageKey(storageKey);
        document.setStatus("UPLOADED");

        try {
            int rows = documentMapper.insert(document);
            if (rows != 1 || document.getId() == null) {
                throw new IllegalStateException("上传元数据保存失败");
            }
        } catch (RuntimeException e) {
            boolean cleaned = fileStorageService.delete(storageKey);
            if (!cleaned) {
                // LocalFileStorageService 已记录具体 I/O 原因；这里补充业务关联信息。
                org.slf4j.LoggerFactory.getLogger(DocumentService.class)
                        .error("上传元数据入库失败后未能清理文件，storageKey={}", storageKey);
            }
            throw e;
        }

        return new UploadResponse(
                document.getId(),
                checked.getOriginalFilename(),
                checked.getSize(),
                checked.getContentType(),
                storageKey,
                true,
                "上传成功：文件已保存到服务器并已记录文档元数据"
        );
    }

    /**
     * Day 17：下载的业务编排，和 upload() 是一对镜像——
     * upload 是"校验 → 落盘 → 入库"，download 是"查库 → 取盘 → 交货"。
     *
     * 三步各自复用已有资产：
     * 1. findById(id)：查不到记录时它自己会抛 DocumentNotFoundException（404）；
     * 2. fileStorageService.load(storageKey)：拿 Resource（文件内容的抽象）；
     * 3. 把 resource + originalFilename 打包成 DocumentDownload 交给 Controller。
     *
     * 文件丢失的翻译发生在这里：load() 的受检异常 FileNotFoundException
     * 是编译器点名要处理的（受检异常 = 监工）。翻译规则：
     * - 对客户端说 404（"要的东西拿不到"）而不是 500——
     *   500 的潜台词是"稍后重试也许就好"，可文件丢了重试无用，得管理员修；
     * - 对内记 error 日志——"库里有记录、盘上没文件"是数据不一致，运维必须可见。
     *
     * 注意 catch 的是 FileNotFoundException 而不是宽泛的 Exception：
     * 真正的服务器故障（磁盘炸了等）应该继续走 FileStorageException 的 500 通道，
     * 误吞成 404 会掩盖故障。
     */
    public DocumentDownload download(Long id) {
        Document document = findById(id);

        Resource resource;
        try {
            resource = fileStorageService.load(document.getStorageKey());
        } catch (FileNotFoundException e) {
            log.error("文档对应的存储文件缺失：id={}, storageKey={}",
                    id, document.getStorageKey());
            throw new DocumentNotFoundException(id, "文档存在，但文件已丢失，请联系管理员");
        }

        return new DocumentDownload(resource, document.getOriginalFilename());
    }

    /**
     * Day 19：读取文档内容（目前仅支持 Markdown，纯文本可直接读成字符串）。
     *
     * 编排四步，三种失败全部走既有通道：
     * 1. findById(id)：文档不存在 → DocumentNotFoundException → 404；
     * 2. 类型检查：只信服务器入库的 file_type（upload 时由扩展名算出，客户端改不了），
     *    绝不信任浏览器自报的 Content-Type；不支持 → IllegalArgumentException → 400；
     * 3. readText(storageKey)：文件丢失 → FileNotFoundException，翻译手法与 download()
     *    相同（404 + 如实提示），越界/超大/IO 错则由 readText 内部抛 FileStorageException → 500；
     * 4. 打包 DocumentContentResponse，由 Controller 转 JSON。
     *
     * PDF/DOCX 是二进制格式，解析需要引入重依赖，今天不做——本方法就是将来
     * 扩展解析能力时唯一需要改的地方（在第 2 步放行更多类型、第 3 步换解析器）。
     */
    public DocumentContentResponse readContent(Long id) {
        Document document = findById(id);

        // 常量放 equalsIgnoreCase 左侧：file_type 为 null（早期 JSON 创建的记录）时
        // 返回 false 而不是 NPE。忽略大小写防的是数据库被手工改过的情况。
        if (!"MD".equalsIgnoreCase(document.getFileType())) {
            throw new IllegalArgumentException(
                    "该文档类型不支持内容读取，目前仅支持 Markdown（.md）");
        }

        String content;
        try {
            content = fileStorageService.readText(document.getStorageKey());
        } catch (FileNotFoundException e) {
            log.error("文档内容对应的存储文件缺失：id={}, storageKey={}",
                    id, document.getStorageKey());
            throw new DocumentNotFoundException(id, "文档存在，但文件已丢失，请联系管理员");
        }

        return new DocumentContentResponse(
                document.getId(),
                document.getOriginalFilename(),
                content,
                content.length()
        );
    }

    /**
     * 只保留最后一个 / 或 \ 之后的部分。
     * 例如 "C:\docs\报告.pdf" → "报告.pdf"。
     */
    private static String extractFilename(String originalName) {
        int slash = Math.max(originalName.lastIndexOf('/'), originalName.lastIndexOf('\\'));
        return slash >= 0 ? originalName.substring(slash + 1) : originalName;
    }

    /**
     * 取最后一个点之后的部分并转小写，统一口径比较。
     * 没有点、或点在结尾（如 "report."），都视为没有扩展名。
     */
    private static String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }
}
