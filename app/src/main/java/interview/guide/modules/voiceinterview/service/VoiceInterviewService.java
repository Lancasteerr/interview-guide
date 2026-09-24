package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.SessionMetaDTO;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import interview.guide.modules.voiceinterview.entity.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.entity.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 语音面试服务门面，保留原有事务入口并委托会话、消息和评测子服务。
 */
@Service
@Slf4j
public class VoiceInterviewService {

  private final VoiceInterviewSessionLifecycleService lifecycleService;
  private final VoiceInterviewMessageService messageService;
  private final VoiceInterviewEvaluationLifecycleService evaluationService;

  public VoiceInterviewService(
      VoiceInterviewSessionRepository sessionRepository,
      VoiceInterviewMessageRepository messageRepository,
      VoiceInterviewEvaluationRepository evaluationRepository,
      RedissonClient redissonClient,
      VoiceInterviewProperties properties,
      VoiceEvaluateStreamProducer voiceEvaluateStreamProducer,
      LlmProviderRegistry llmProviderRegistry) {
    VoiceInterviewSessionCacheService sessionCacheService =
        new VoiceInterviewSessionCacheService(redissonClient);
    VoiceInterviewPhaseService phaseService = new VoiceInterviewPhaseService(properties);
    this.messageService = new VoiceInterviewMessageService(sessionRepository, messageRepository);
    this.evaluationService = new VoiceInterviewEvaluationLifecycleService(
        sessionRepository, voiceEvaluateStreamProducer, sessionCacheService);
    this.lifecycleService = new VoiceInterviewSessionLifecycleService(
        sessionRepository,
        evaluationRepository,
        properties,
        sessionCacheService,
        phaseService,
        messageService,
        evaluationService
    );
  }

  @Transactional
  public SessionResponseDTO createSession(CreateSessionRequest request) {
    return lifecycleService.createSession(request);
  }

  @Transactional
  public void endSessionIfInProgress(String sessionId) {
    lifecycleService.endSessionIfInProgress(sessionId);
  }

  @Transactional
  public void endSession(String sessionId) {
    lifecycleService.endSession(sessionId);
  }

  public VoiceInterviewSessionEntity getSession(String sessionId) {
    return lifecycleService.getSession(sessionId);
  }

  public VoiceInterviewSessionEntity getSession(Long sessionId) {
    return lifecycleService.getSession(sessionId);
  }

  @Transactional
  public void startPhase(String sessionId, String phaseStr) {
    lifecycleService.startPhase(sessionId, phaseStr);
  }

  public VoiceInterviewSessionEntity.InterviewPhase getCurrentPhase(String sessionId) {
    return lifecycleService.getCurrentPhase(sessionId);
  }

  @Transactional
  public void saveMessage(String sessionId, String userText, String aiText) {
    VoiceInterviewSessionEntity session = getSession(sessionId);
    if (session == null) {
      return;
    }
    messageService.saveMessage(parseSessionId(sessionId), session, userText, aiText);
  }

  public List<VoiceInterviewMessageEntity> getConversationHistory(String sessionId) {
    return messageService.getConversationHistory(parseSessionId(sessionId));
  }

  public Optional<VoiceInterviewMessageEntity> loadSummaryRow(String sessionId) {
    return messageService.loadSummaryRow(parseSessionId(sessionId));
  }

  @Transactional(rollbackFor = Exception.class)
  public void saveSummaryRow(String sessionId, String summary, int coveredSequenceNum) {
    messageService.saveSummaryRow(
        parseSessionId(sessionId), sessionId, summary, coveredSequenceNum);
  }

  @Deprecated
  @Transactional(rollbackFor = Exception.class)
  public void saveSummaryRowLegacy(String sessionId, String summary, int coveredTurns) {
    messageService.saveSummaryRowLegacy(parseSessionId(sessionId), sessionId, summary, coveredTurns);
  }

  public List<VoiceInterviewMessageDTO> getConversationHistoryDTO(String sessionId) {
    return getConversationHistory(sessionId).stream()
        .map(message -> VoiceInterviewMessageDTO.builder()
            .id(message.getId())
            .sessionId(message.getSessionId())
            .messageType(message.getMessageType())
            .phase(message.getPhase() != null ? message.getPhase().name() : null)
            .userRecognizedText(message.getUserRecognizedText())
            .aiGeneratedText(message.getAiGeneratedText())
            .timestamp(message.getTimestamp())
            .sequenceNum(message.getSequenceNum())
            .build())
        .collect(Collectors.toList());
  }

  @Transactional
  public void pauseSession(String sessionId, String reason) {
    lifecycleService.pauseSession(sessionId, reason);
  }

  @Transactional
  public SessionResponseDTO resumeSession(String sessionId) {
    return lifecycleService.resumeSession(sessionId);
  }

  public List<SessionMetaDTO> getAllSessions(String userId, String status) {
    return lifecycleService.getAllSessions(userId, status);
  }

  public SessionResponseDTO getSessionDTO(Long sessionId) {
    return lifecycleService.getSessionDTO(sessionId);
  }

  public boolean shouldTransitionToNextPhase(
      VoiceInterviewSessionEntity session, LocalDateTime phaseStartTime, int questionCount) {
    return lifecycleService.shouldTransitionToNextPhase(session, phaseStartTime, questionCount);
  }

  public VoiceInterviewSessionEntity.InterviewPhase getNextPhase(
      VoiceInterviewSessionEntity session) {
    return lifecycleService.getNextPhase(session);
  }

  public void updateEvaluateStatus(Long sessionId, AsyncTaskStatus status, String error) {
    evaluationService.updateEvaluateStatus(sessionId, status, error);
  }

  @Transactional
  public void triggerEvaluation(Long sessionId) {
    evaluationService.triggerEvaluation(sessionId);
  }

  @Transactional
  public void deleteSession(Long sessionId) {
    lifecycleService.deleteSession(sessionId);
  }

  @Transactional
  public int cleanupStaleSessions() {
    return lifecycleService.cleanupStaleSessions();
  }

  private Long parseSessionId(String sessionId) {
    if (sessionId == null) {
      return null;
    }
    try {
      return Long.parseLong(sessionId);
    } catch (NumberFormatException e) {
      log.error("Invalid session ID format: {}", sessionId, e);
      return null;
    }
  }
}
