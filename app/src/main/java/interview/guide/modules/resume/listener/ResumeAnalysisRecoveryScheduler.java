package interview.guide.modules.resume.listener;

import interview.guide.common.async.recovery.ResumeAnalysisRecoveryProperties;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历分析卡住任务恢复调度器（P1-07）。语义与 VectorizeRecoveryScheduler 一致。
 */
@Slf4j
@Component
public class ResumeAnalysisRecoveryScheduler {

  private final ResumeRepository resumeRepository;
  private final AnalyzeStreamProducer producer;
  private final ResumeAnalysisRecoveryProperties properties;

  public ResumeAnalysisRecoveryScheduler(ResumeRepository resumeRepository,
                                         AnalyzeStreamProducer producer,
                                         ResumeAnalysisRecoveryProperties properties) {
    this.resumeRepository = resumeRepository;
    this.producer = producer;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${app.async.recovery.resume-analyze.interval-ms:60000}",
      initialDelayString = "${app.async.recovery.resume-analyze.interval-ms:60000}")
  public void recoverStuckTasks() {
    if (!properties.isEnabled()) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    List<ResumeEntity> stalePending = resumeRepository.findStaleByAnalyzeStatus(
        AsyncTaskStatus.PENDING, now.minus(properties.getPendingThreshold()),
        PageRequest.of(0, properties.getBatchSize()));
    for (ResumeEntity resume : stalePending) {
      recover(resume.getId(), true);
    }
    List<ResumeEntity> staleProcessing = resumeRepository.findStaleByAnalyzeStatus(
        AsyncTaskStatus.PROCESSING, now.minus(properties.getProcessingThreshold()),
        PageRequest.of(0, properties.getBatchSize()));
    for (ResumeEntity resume : staleProcessing) {
      int reset = resumeRepository.resetStaleAnalyzeProcessing(
          resume.getId(), LocalDateTime.now().minus(properties.getProcessingThreshold()), LocalDateTime.now());
      if (reset == 1) {
        recover(resume.getId(), false);
      }
    }
  }

  private void recover(Long resumeId, boolean alreadyPending) {
    LocalDateTime now = LocalDateTime.now();
    if (alreadyPending) {
      int touched = resumeRepository.touchQueuedAnalyzeForRecovery(
          resumeId, LocalDateTime.now().minus(properties.getPendingThreshold()), now);
      if (touched != 1) {
        return;
      }
    }
    resumeRepository.incrementAnalyzeRecoveryCount(resumeId);
    ResumeEntity resume = resumeRepository.findById(resumeId).orElse(null);
    if (resume == null) {
      return;
    }
    if (resume.getAnalyzeRecoveryCount() >= properties.getMaxRecoveryCount()) {
      resumeRepository.failAnalyzeIfPending(resumeId,
          "自动恢复次数达到上限（" + resume.getAnalyzeRecoveryCount() + "），请手动重试", LocalDateTime.now());
      log.warn("简历分析任务自动恢复达到上限转 FAILED: resumeId={}, recoveryCount={}",
          resumeId, resume.getAnalyzeRecoveryCount());
      return;
    }
    boolean sent = producer.sendAnalyzeTask(resumeId);
    log.info("简历分析卡住任务已补投: resumeId={}, sent={}, recoveryCount={}",
        resumeId, sent, resume.getAnalyzeRecoveryCount());
  }
}
