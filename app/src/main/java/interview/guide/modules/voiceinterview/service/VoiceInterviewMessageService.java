package interview.guide.modules.voiceinterview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.voiceinterview.entity.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.entity.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * 语音面试消息和上下文摘要持久化服务。
 *
 * <p>只负责消息行的查询、顺序号、答案回填和摘要覆盖边界，事务由上层门面保持。</p>
 */
@Slf4j
final class VoiceInterviewMessageService {

  private final VoiceInterviewSessionRepository sessionRepository;
  private final VoiceInterviewMessageRepository messageRepository;

  VoiceInterviewMessageService(
      VoiceInterviewSessionRepository sessionRepository,
      VoiceInterviewMessageRepository messageRepository) {
    this.sessionRepository = sessionRepository;
    this.messageRepository = messageRepository;
  }

  void saveMessage(Long sessionId, VoiceInterviewSessionEntity session, String userText, String aiText) {
    String normalizedUserText = VoiceInterviewMessageEntity.trimToNull(userText);
    String normalizedAiText = VoiceInterviewMessageEntity.trimToNull(aiText);

    boolean answerAttached = normalizedUserText != null
        && fillLatestUnansweredQuestion(sessionId, normalizedUserText);
    if (normalizedAiText == null) {
      return;
    }

    VoiceInterviewMessageEntity message = VoiceInterviewMessageEntity.builder()
        .sessionId(sessionId)
        .messageType("DIALOGUE")
        .phase(session.getCurrentPhase())
        .userRecognizedText(normalizedUserText != null && !answerAttached
            ? normalizedUserText
            : null)
        .aiGeneratedText(normalizedAiText)
        .sequenceNum(getNextSequenceNum(sessionId))
        .build();

    messageRepository.save(message);
    log.debug("Saved message for session: {}, phase: {}, sequence: {}",
        sessionId, session.getCurrentPhase(), message.getSequenceNum());
  }

  private boolean fillLatestUnansweredQuestion(Long sessionId, String userText) {
    return messageRepository
        .findFirstBySessionIdAndUserRecognizedTextIsNullAndAiGeneratedTextIsNotNullOrderBySequenceNumDesc(
            sessionId)
        .map(message -> {
          message.setUserRecognizedText(userText);
          messageRepository.save(message);
          log.debug("Filled answer for voice message: sessionId={}, sequence={}",
              sessionId, message.getSequenceNum());
          return true;
        })
        .orElse(false);
  }

  List<VoiceInterviewMessageEntity> getConversationHistory(Long sessionId) {
    return messageRepository.findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
        sessionId, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY);
  }

  Optional<VoiceInterviewMessageEntity> loadSummaryRow(Long sessionId) {
    return messageRepository.findFirstBySessionIdAndMessageTypeOrderBySequenceNumAsc(
        sessionId, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY);
  }

  void saveSummaryRow(Long sessionId, String sessionIdText, String summary, int coveredSequenceNum) {
    lockSession(sessionId, sessionIdText);
    VoiceInterviewMessageEntity row = loadSummaryRow(sessionId)
        .orElseGet(() -> VoiceInterviewMessageEntity.builder()
            .sessionId(sessionId)
            .messageType(VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)
            .sequenceNum(-1)
            .build());
    Integer existingBoundary = row.getSummaryCoveredSequenceNum();
    if (existingBoundary != null && coveredSequenceNum < existingBoundary) {
      log.warn("摘要覆盖边界回退被拒绝: sessionId={}, existing={}, incoming={}",
          sessionIdText, existingBoundary, coveredSequenceNum);
      return;
    }
    row.setAiGeneratedText(summary);
    row.setSequenceNum(Math.min(row.getSequenceNum() != null ? row.getSequenceNum() : -1, -1));
    row.setSummaryCoveredSequenceNum(coveredSequenceNum);
    messageRepository.save(row);
  }

  void saveSummaryRowLegacy(Long sessionId, String sessionIdText, String summary, int coveredTurns) {
    lockSession(sessionId, sessionIdText);
    VoiceInterviewMessageEntity row = loadSummaryRow(sessionId)
        .orElseGet(() -> VoiceInterviewMessageEntity.builder()
            .sessionId(sessionId)
            .messageType(VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)
            .build());
    row.setAiGeneratedText(summary);
    row.setSequenceNum(-(coveredTurns + 1));
    messageRepository.save(row);
  }

  long countDialogueMessages(Long sessionId) {
    return messageRepository.countBySessionIdAndMessageTypeNot(
        sessionId, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY);
  }

  void deleteMessages(Long sessionId) {
    messageRepository.deleteBySessionId(sessionId);
  }

  private int getNextSequenceNum(Long sessionId) {
    return (int) countDialogueMessages(sessionId) + 1;
  }

  private void lockSession(Long sessionId, String sessionIdText) {
    sessionRepository.findByIdForUpdate(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionIdText));
  }
}
