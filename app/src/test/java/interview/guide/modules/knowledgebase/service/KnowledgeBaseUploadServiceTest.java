package interview.guide.modules.knowledgebase.service;

import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库上传异步化测试")
class KnowledgeBaseUploadServiceTest {

  @Mock
  private KnowledgeBaseParseService parseService;
  @Mock
  private KnowledgeBasePersistenceService persistenceService;
  @Mock
  private FileStorageService storageService;
  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock
  private FileValidationService fileValidationService;
  @Mock
  private FileHashService fileHashService;
  @Mock
  private VectorizeStreamProducer vectorizeStreamProducer;

  private KnowledgeBaseUploadService service;

  @BeforeEach
  void setUp() {
    service = new KnowledgeBaseUploadService(parseService, persistenceService, storageService,
        knowledgeBaseRepository, fileValidationService, fileHashService, vectorizeStreamProducer);
  }

  @Test
  @DisplayName("上传不在请求线程解析正文，只投递 kbId 且响应不含 contentLength")
  void shouldNotParseContentInRequestThread() {
    MultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {1, 2, 3});
    when(parseService.detectContentType(file)).thenReturn("application/pdf");
    when(fileHashService.calculateHash(file)).thenReturn("hash-1");
    when(knowledgeBaseRepository.findByFileHash("hash-1")).thenReturn(Optional.empty());
    when(storageService.uploadKnowledgeBase(file)).thenReturn("kb/1");
    when(storageService.getFileUrl("kb/1")).thenReturn("http://rustfs/kb/1");
    KnowledgeBaseEntity saved = new KnowledgeBaseEntity();
    saved.setId(11L);
    saved.setName("a.pdf");
    saved.setFileSize(3L);
    when(persistenceService.saveKnowledgeBase(any(), anyString(), any(), anyString(), anyString(), anyString()))
        .thenReturn(saved);

    Map<String, Object> result = service.uploadKnowledgeBase(file, "测试知识库", "JVM");

    verify(parseService, never()).parseContent(any(MultipartFile.class));
    verify(parseService, never()).parseContent(any(), anyString());
    verify(parseService, never()).downloadAndParseContent(anyString(), anyString());
    verify(vectorizeStreamProducer).sendVectorizeTask(11L);

    @SuppressWarnings("unchecked")
    Map<String, Object> knowledgeBase = (Map<String, Object>) result.get("knowledgeBase");
    assertThat(knowledgeBase).doesNotContainKey("contentLength");
    assertThat(knowledgeBase).containsEntry("vectorStatus", "PENDING");
  }

  @Test
  @DisplayName("重复上传响应与新上传契约一致：含 vectorStatus，不含 contentLength")
  void shouldReturnConsistentContractForDuplicateUpload() {
    MultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {1, 2, 3});
    when(parseService.detectContentType(file)).thenReturn("application/pdf");
    when(fileHashService.calculateHash(file)).thenReturn("hash-dup");
    KnowledgeBaseEntity existing = new KnowledgeBaseEntity();
    existing.setId(21L);
    existing.setName("a.pdf");
    existing.setFileSize(3L);
    existing.setVectorStatus(interview.guide.modules.knowledgebase.model.VectorStatus.COMPLETED);
    when(knowledgeBaseRepository.findByFileHash("hash-dup")).thenReturn(Optional.of(existing));
    when(persistenceService.handleDuplicateKnowledgeBase(existing, "hash-dup")).thenReturn(Map.of(
        "knowledgeBase", Map.of(
            "id", existing.getId(),
            "name", existing.getName(),
            "category", "",
            "fileSize", existing.getFileSize(),
            "vectorStatus", existing.getVectorStatus().name()),
        "storage", Map.of("fileKey", "kb/21", "fileUrl", "http://rustfs/kb/21"),
        "duplicate", true));

    Map<String, Object> result = service.uploadKnowledgeBase(file, null, null);

    @SuppressWarnings("unchecked")
    Map<String, Object> knowledgeBase = (Map<String, Object>) result.get("knowledgeBase");
    assertThat(knowledgeBase).doesNotContainKey("contentLength");
    assertThat(knowledgeBase).containsEntry("vectorStatus", "COMPLETED");
    assertThat((Boolean) result.get("duplicate")).isTrue();
    verify(vectorizeStreamProducer, never()).sendVectorizeTask(anyLong());
  }

  @Test
  @DisplayName("重新向量化只校验存储信息并投递 ID，不下载解析")
  void shouldRevectorizeWithIdOnly() {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(11L);
    kb.setStorageKey("kb/11");
    kb.setOriginalFilename("a.pdf");
    when(knowledgeBaseRepository.findById(11L)).thenReturn(Optional.of(kb));

    service.revectorize(11L);

    verify(parseService, never()).downloadAndParseContent(anyString(), anyString());
    verify(persistenceService).updateVectorStatusToPending(11L);
    verify(vectorizeStreamProducer).sendVectorizeTask(11L);
  }
}
