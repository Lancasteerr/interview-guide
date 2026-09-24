package interview.guide.modules.voiceinterview.service;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.entity.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 语音面试评测生命周期协作者，负责状态更新、任务投递和过期任务恢复。
 */
@Slf4j
final class VoiceInterviewEvaluationLifecycleService {

  private static final Duration PENDING_EVALUATION_REQUEUE_DELAY = Duration.ofMinutes(3);
  private static final Duration PROCESSING_EVALUATION_TIMEOUT = Duration.ofMinutes(30);

  private final VoiceInterviewSessionRepository sessionRepository;
  private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;
  private final VoiceInterviewSessionCacheService sessionCacheService;

  VoiceInterviewEvaluationLifecycleService(
      VoiceInterviewSessionRepository sessionRepository,
      VoiceEvaluateStreamProducer voiceEvaluateStreamProducer,
      VoiceInterviewSessionCacheService sessionCacheService) {
    this.sessionRepository = sessionRepository;
    this.voiceEvaluateStreamProducer = voiceEvaluateStreamProducer;
    this.sessionCacheService = sessionCacheService;
  }

  void updateEvaluateStatus(Long sessionId, AsyncTaskStatus status, String error) {
    try {
      sessionRepository.findById(sessionId).ifPresent(session -> {
        session.setEvaluateStatus(status);
        session.setEvaluateError(error);
        sessionRepository.save(session);
        sessionCacheService.invalidate(sessionId);
        log.debug("Evaluation status updated: sessionId={}, status={}", sessionId, status);
      });
    } catch (Exception e) {
      log.error("Failed to update evaluation status: sessionId={}, status={}, error={}",
          sessionId, status, e.getMessage(), e);
    }
  }

  void triggerEvaluation(Long sessionId) {
    updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
    sendTaskAfterCommit(sessionId);
  }

  void sendTaskAfterCommit(Long sessionId) {
    Runnable sendTask = () -> voiceEvaluateStreamProducer.sendEvaluateTask(sessionId.toString());
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      sendTask.run();
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        sendTask.run();
      }
    });
  }

  int recoverStaleEvaluations() {
    int recovered = 0;

    LocalDateTime pendingStaleThreshold = LocalDateTime.now()
        .minus(PENDING_EVALUATION_REQUEUE_DELAY);
    List<VoiceInterviewSessionEntity> pendingEvals = sessionRepository
        .findByEvaluateStatusAndUpdatedAtBefore(AsyncTaskStatus.PENDING, pendingStaleThreshold);

    for (VoiceInterviewSessionEntity session : pendingEvals) {
      log.warn("Requeueing stale PENDING evaluation for session {}, last updated at {}",
          session.getId(), session.getUpdatedAt());
      session.setEvaluateError(null);
      session.setUpdatedAt(LocalDateTime.now());
      sessionRepository.save(session);
      sessionCacheService.invalidate(session.getId());
      sendTaskAfterCommit(session.getId());
      recovered++;
    }

    LocalDateTime evalStaleThreshold = LocalDateTime.now()
        .minus(PROCESSING_EVALUATION_TIMEOUT);
    List<VoiceInterviewSessionEntity> stuckEvals = sessionRepository
        .findByEvaluateStatusAndUpdatedAtBefore(AsyncTaskStatus.PROCESSING, evalStaleThreshold);

    for (VoiceInterviewSessionEntity session : stuckEvals) {
      log.info("Resetting stuck PROCESSING evaluation for session {}", session.getId());
      session.setEvaluateStatus(AsyncTaskStatus.FAILED);
      session.setEvaluateError("评估超时，请重新触发");
      sessionRepository.save(session);
      sessionCacheService.invalidate(session.getId());
      recovered++;
    }

    return recovered;
  }
}
