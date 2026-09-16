package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 通用文档解析服务
 * 使用 Apache Tika 解析多种文档格式，提取文本内容
 * 供知识库和简历模块共同使用
 */
@Slf4j
@Service
public class DocumentParseService {

    private static final int MAX_TEXT_LENGTH = 5 * 1024 * 1024; // 5MB

    private final TextCleaningService textCleaningService;
    private final ThreadPoolExecutor parseExecutor;
    private final DocumentParseProperties properties;

    public DocumentParseService(TextCleaningService textCleaningService) {
        this(textCleaningService, null, new DocumentParseProperties());
    }

    @Autowired
    public DocumentParseService(TextCleaningService textCleaningService,
                                @Qualifier("documentParseExecutor")
                                ThreadPoolExecutor parseExecutor,
                                DocumentParseProperties properties) {
        this.textCleaningService = textCleaningService;
        this.parseExecutor = parseExecutor;
        this.properties = properties;
    }

    /**
     * 解析上传的文件，提取文本内容
     *
     * @param file 上传的文件（支持PDF、DOCX、DOC、TXT、MD等）
     * @return 提取的文本内容
     */
    public String parseContent(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        log.info("开始解析文件: {}", fileName);

        // 处理空文件
        if (file.isEmpty() || file.getSize() == 0) {
            log.warn("文件为空: {}", fileName);
            return "";
        }

        try {
            String content = parseWithTimeout(file.getBytes());
            String cleanedContent = textCleaningService.cleanText(content);
            log.info("文件解析成功，提取文本长度: {} 字符", cleanedContent.length());
            return cleanedContent;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("文件读取失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件读取失败");
        }
    }

    /**
     * 解析字节数组形式的文件内容
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（用于日志）
     * @return 提取的文本内容
     */
    public String parseContent(byte[] fileBytes, String fileName) {
        log.info("开始解析文件（从字节数组）: {}", fileName);

        // 处理空文件
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("文件字节数组为空: {}", fileName);
            return "";
        }

        try {
            String content = parseWithTimeout(fileBytes);
            String cleanedContent = textCleaningService.cleanText(content);
            log.info("文件解析成功，提取文本长度: {} 字符", cleanedContent.length());
            return cleanedContent;
        } catch (BusinessException e) {
            throw e;
        }
    }

    /**
     * 核心解析方法：使用显式 Parser + Context 方式解析文档
     * <p>
     * 优化点：
     * 1. 使用 BodyContentHandler 只提取正文内容
     * 2. 禁用 EmbeddedDocumentExtractor，不解析嵌入资源（图片、附件）
     * 3. 配置 PDFParserConfig，关闭图片和注释提取
     * 4. 显式指定 Parser 到 Context，增强健壮性
     *
     * @param inputStream 文件输入流
     * @return 提取的文本内容
     * @throws IOException     IO 异常
     * @throws TikaException   Tika 解析异常
     * @throws SAXException    SAX 解析异常
     */
    private String parseContent(InputStream inputStream) throws IOException, TikaException, SAXException {
        // 1. 创建自动检测解析器
        AutoDetectParser parser = new AutoDetectParser();

        // 2. 创建内容处理器，只接收正文，限制最大长度为 5MB
        BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);

        // 3. 创建元数据对象
        Metadata metadata = new Metadata();

        // 4. 创建解析上下文
        ParseContext context = new ParseContext();

        // 5. 显式指定 Parser 到 Context（增强健壮性）
        context.set(Parser.class, parser);

        // 6. 禁用嵌入文档解析（关键：避免提取图片引用和临时文件路径）
        context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());

        // 7. PDF 专用配置：关闭图片提取，按位置排序文本
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setSortByPosition(true); // 按 x/y 坐标排序文本，改善多栏布局解析顺序
        // 注意：Tika 2.9.2 中 setExtractAnnotations 方法可能不存在，关闭图片提取已足够
        context.set(PDFParserConfig.class, pdfConfig);

        // 8. 执行解析
        parser.parse(inputStream, handler, metadata, context);

        // 9. 返回提取的文本内容
        return handler.toString();
    }

    private String parseWithTimeout(byte[] fileBytes) {
        if (parseExecutor == null) {
            return parseDirect(fileBytes);
        }
        var future = parseExecutor.submit(() -> {
            try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
                return parseContent(inputStream);
            }
        });
        try {
            return future.get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("文件解析超时: timeoutMs={}", properties.getTimeout().toMillis(),
                ErrorLogSanitizer.forLogging(e));
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析超时，请检查文件内容");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("文件解析失败: {}", ErrorLogSanitizer.summarize(cause),
                ErrorLogSanitizer.forLogging(cause));
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败");
        }
    }

    private String parseDirect(byte[] fileBytes) {
        try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            return parseContent(inputStream);
        } catch (IOException | TikaException | SAXException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败");
        }
    }

    /**
     * 从存储下载文件并解析内容
     *
     * @param storageService   文件存储服务
     * @param storageKey       存储键
     * @param originalFilename 原始文件名
     * @return 提取的文本内容
     */
    public String downloadAndParseContent(FileStorageService storageService, String storageKey, String originalFilename) {
        try {
            byte[] fileBytes = storageService.downloadFile(storageKey);
            if (fileBytes == null || fileBytes.length == 0) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载文件失败");
            }
            return parseContent(fileBytes, originalFilename);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("下载并解析文件失败: storageKey={}, error={}", storageKey,
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载并解析文件失败");
        }
    }
}
