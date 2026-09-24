package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.knowledgebase.config.KnowledgeBaseVectorProperties;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库文本切片、向量化写入和向量任务状态处理。
 */
@Slf4j
final class KnowledgeBaseVectorizationService {

  private static final int MAX_BATCH_SIZE = 10;
  private static final String TEMP_KB_ID_PREFIX = "pending:";
  private static final String METADATA_KB_ID = "kb_id";
  private static final String METADATA_TARGET_KB_ID = "kb_target_id";
  private static final String METADATA_VECTOR_JOB_ID = "kb_vector_job_id";
  private static final String METADATA_CHUNK_STRATEGY = "chunk_strategy";

  private final VectorStore vectorStore;
  private final TextSplitter textSplitter;
  private final VectorRepository vectorRepository;
  private final TransactionalExecutor transactionalExecutor;
  private final KnowledgeBaseVectorProperties vectorProperties;
  private final KnowledgeBasePersistenceService persistenceService;

  KnowledgeBaseVectorizationService(
      VectorStore vectorStore,
      VectorRepository vectorRepository,
      TransactionalExecutor transactionalExecutor,
      KnowledgeBaseVectorProperties vectorProperties,
      KnowledgeBasePersistenceService persistenceService) {
    this.vectorStore = vectorStore;
    this.vectorRepository = vectorRepository;
    this.transactionalExecutor = transactionalExecutor;
    this.vectorProperties = vectorProperties;
    this.persistenceService = persistenceService;
    this.textSplitter = vectorProperties.createTextSplitter();
  }

  void vectorizeAndStore(Long knowledgeBaseId, String content, String attemptId,
      Runnable progressHeartbeat) {
    String jobId = null;
    try {
      if (knowledgeBaseId == null) {
        throw new IllegalArgumentException("knowledgeBaseId不能为空");
      }
      jobId = UUID.randomUUID().toString();
      log.info("开始向量化知识库: kbId={}, jobId={}, contentLength={}",
          knowledgeBaseId, jobId, content.length());

      List<Document> chunks = textSplitter.apply(List.of(new Document(content)));
      log.info("文本分块完成: {} 个chunks", chunks.size());
      applyPendingMetadata(chunks, knowledgeBaseId, jobId);
      progressHeartbeat.run();

      int totalChunks = chunks.size();
      int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
      log.info("开始分批向量化: 总共 {} 个chunks，分 {} 批处理，每批最多 {} 个",
          totalChunks, batchCount, MAX_BATCH_SIZE);
      for (int i = 0; i < batchCount; i++) {
        int start = i * MAX_BATCH_SIZE;
        int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
        List<Document> batch = chunks.subList(start, end);
        log.debug("处理第 {}/{} 批: chunks {}-{}", i + 1, batchCount, start + 1, end);
        vectorStore.add(batch);
        progressHeartbeat.run();
      }

      String configJson = vectorConfigJson();
      if (attemptId != null && persistenceService != null) {
        persistenceService.activateVectorJobAndUpdateSnapshot(
            knowledgeBaseId, attemptId, jobId, totalChunks, configJson);
      } else {
        activateVectorJob(knowledgeBaseId, jobId);
        updateVectorizationSnapshot(knowledgeBaseId, totalChunks, configJson);
      }
      log.info("知识库向量化完成: kbId={}, jobId={}, chunks={}, batches={}",
          knowledgeBaseId, jobId, totalChunks, batchCount);
    } catch (Exception e) {
      cleanupPendingVectorJob(knowledgeBaseId, jobId);
      log.error("向量化知识库失败: kbId={}, jobId={}, error={}",
          knowledgeBaseId, jobId, ErrorLogSanitizer.summarize(e),
          ErrorLogSanitizer.forLogging(e));
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
          "向量化知识库失败");
    }
  }

  private void applyPendingMetadata(List<Document> chunks, Long knowledgeBaseId, String jobId) {
    String pendingKbId = TEMP_KB_ID_PREFIX + knowledgeBaseId + ":" + jobId;
    chunks.forEach(chunk -> {
      chunk.getMetadata().put(METADATA_KB_ID, pendingKbId);
      chunk.getMetadata().put(METADATA_TARGET_KB_ID, knowledgeBaseId.toString());
      chunk.getMetadata().put(METADATA_VECTOR_JOB_ID, jobId);
      chunk.getMetadata().put(METADATA_CHUNK_STRATEGY, vectorProperties.getStrategyVersion());
    });
  }

  private void activateVectorJob(Long knowledgeBaseId, String jobId) {
    runVectorRepositoryMutation(() -> {
      vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
      vectorRepository.promoteVectorJob(knowledgeBaseId, jobId);
    });
  }

  private void updateVectorizationSnapshot(Long knowledgeBaseId, int chunkCount, String configJson) {
    if (persistenceService == null) {
      return;
    }
    Runnable update = () -> persistenceService.updateVectorizationSnapshot(
        knowledgeBaseId, chunkCount, configJson);
    if (transactionalExecutor == null) {
      update.run();
      return;
    }
    transactionalExecutor.run(update);
  }

  private String vectorConfigJson() {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("splitter", vectorProperties.getSplitter());
    config.put("chunkSize", vectorProperties.getChunkSize());
    config.put("minChunkSizeChars", vectorProperties.getMinChunkSizeChars());
    config.put("minChunkLengthToEmbed", vectorProperties.getMinChunkLengthToEmbed());
    config.put("maxNumChunks", vectorProperties.getMaxNumChunks());
    config.put("keepSeparator", vectorProperties.isKeepSeparator());
    config.put("punctuationMarks", vectorProperties.getPunctuationMarks());
    config.put("strategyVersion", vectorProperties.getStrategyVersion());
    try {
      return new tools.jackson.databind.ObjectMapper().writeValueAsString(config);
    } catch (tools.jackson.core.JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "向量化配置快照序列化失败");
    }
  }

  private void cleanupPendingVectorJob(Long knowledgeBaseId, String jobId) {
    if (jobId == null) {
      return;
    }
    try {
      runVectorRepositoryMutation(() -> vectorRepository.deleteByVectorJobId(jobId));
    } catch (Exception cleanupError) {
      log.warn("清理临时向量数据失败，可后续按 jobId 补偿: kbId={}, jobId={}, error={}",
          knowledgeBaseId, jobId, ErrorLogSanitizer.summarize(cleanupError),
          ErrorLogSanitizer.forLogging(cleanupError));
    }
  }

  private void runVectorRepositoryMutation(Runnable action) {
    if (transactionalExecutor == null) {
      action.run();
      return;
    }
    transactionalExecutor.run(action);
  }
}
