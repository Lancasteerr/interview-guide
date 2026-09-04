package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.recovery.VectorizeRecoveryProperties;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库向量化卡住任务恢复调度器（P1-07）。
 *
 * <p>PENDING 超阈值（投递丢失）与 PROCESSING 超阈值且无心跳（消费者死亡）两种场景补投。
 * 恢复前通过条件更新原子推进 vector_updated_at，保证一个调度周期内同一实体只补投一次；
 * recovery_count 达到上限转 FAILED。外部投递在事务外完成。
 */
@Slf4j
@Component
public class VectorizeRecoveryScheduler {

  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final VectorizeStreamProducer producer;
  private final VectorizeRecoveryProperties properties;

  public VectorizeRecoveryScheduler(KnowledgeBaseRepository knowledgeBaseRepository,
                                    VectorizeStreamProducer producer,
                                    VectorizeRecoveryProperties properties) {
    this.knowledgeBaseRepository = knowledgeBaseRepository;
    this.producer = producer;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${app.async.recovery.vectorize.interval-ms:60000}",
      initialDelayString = "${app.async.recovery.vectorize.interval-ms:60000}")
  public void recoverStuckTasks() {
    if (!properties.isEnabled()) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    List<KnowledgeBaseEntity> stalePending = knowledgeBaseRepository.findStaleByVectorStatus(
        VectorStatus.PENDING, now.minus(properties.getPendingThreshold()),
        PageRequest.of(0, properties.getBatchSize()));
    for (KnowledgeBaseEntity kb : stalePending) {
      recover(kb.getId(), true);
    }
    List<KnowledgeBaseEntity> staleProcessing = knowledgeBaseRepository.findStaleByVectorStatus(
        VectorStatus.PROCESSING, now.minus(properties.getProcessingThreshold()),
        PageRequest.of(0, properties.getBatchSize()));
    for (KnowledgeBaseEntity kb : staleProcessing) {
      // PROCESSING 无进展：先条件重置回 PENDING，再走统一的补投路径
      int reset = knowledgeBaseRepository.resetStaleVectorProcessing(
          kb.getId(), LocalDateTime.now().minus(properties.getProcessingThreshold()), LocalDateTime.now());
      if (reset == 1) {
        recover(kb.getId(), false);
      }
    }
  }

  private void recover(Long kbId, boolean alreadyPending) {
    LocalDateTime now = LocalDateTime.now();
    if (alreadyPending) {
      int touched = knowledgeBaseRepository.touchQueuedVectorForRecovery(
          kbId, LocalDateTime.now().minus(properties.getPendingThreshold()), now);
      if (touched != 1) {
        return;   // 已被其他路径推进或恢复
      }
    }
    knowledgeBaseRepository.incrementVectorRecoveryCount(kbId);
    KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId).orElse(null);
    if (kb == null) {
      return;
    }
    if (kb.getVectorRecoveryCount() >= properties.getMaxRecoveryCount()) {
      knowledgeBaseRepository.failVectorUnlessCompleted(kbId,
          "自动恢复次数达到上限（" + kb.getVectorRecoveryCount() + "），请手动重试", LocalDateTime.now());
      log.warn("向量化任务自动恢复达到上限转 FAILED: kbId={}, recoveryCount={}",
          kbId, kb.getVectorRecoveryCount());
      return;
    }
    boolean sent = producer.sendVectorizeTask(kbId);
    log.info("向量化卡住任务已补投: kbId={}, sent={}, recoveryCount={}",
        kbId, sent, kb.getVectorRecoveryCount());
  }
}
