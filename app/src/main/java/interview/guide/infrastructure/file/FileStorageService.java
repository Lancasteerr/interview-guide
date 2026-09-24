package interview.guide.infrastructure.file;

import interview.guide.common.config.StorageConfigProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 业务文件存储兼容门面。
 *
 * <p>业务侧继续通过简历/知识库语义调用，S3 协议和 Key 规则分别由专用组件负责。</p>
 */
@Service
public class FileStorageService {

    private final S3ObjectStorageService objectStorage;
    private final FileStorageKeyService keyService;

    @Autowired
    public FileStorageService(S3ObjectStorageService objectStorage, FileStorageKeyService keyService) {
        this.objectStorage = objectStorage;
        this.keyService = keyService;
    }

    /**
     * 保留测试和旧调用方使用的构造方式。
     */
    public FileStorageService(S3Client s3Client, StorageConfigProperties storageConfig) {
        this(new S3ObjectStorageService(s3Client, storageConfig), new FileStorageKeyService());
    }

    public void init() {
        objectStorage.ensureBucketExists();
    }

    public String uploadResume(MultipartFile file) {
        return uploadFile(file, "resumes");
    }

    public void deleteResume(String fileKey) {
        objectStorage.delete(fileKey);
    }

    public String uploadKnowledgeBase(MultipartFile file) {
        return uploadFile(file, "knowledgebases");
    }

    public void deleteKnowledgeBase(String fileKey) {
        objectStorage.delete(fileKey);
    }

    public byte[] downloadFile(String fileKey) {
        return objectStorage.download(fileKey);
    }

    public boolean fileExists(String fileKey) {
        return objectStorage.exists(fileKey);
    }

    public long getFileSize(String fileKey) {
        return objectStorage.getSize(fileKey);
    }

    public String getFileUrl(String fileKey) {
        return objectStorage.getFileUrl(fileKey);
    }

    public void ensureBucketExists() {
        objectStorage.ensureBucketExists();
    }

    private String uploadFile(MultipartFile file, String prefix) {
        String fileKey = keyService.generate(file.getOriginalFilename(), prefix);
        return objectStorage.upload(file, fileKey);
    }
}
