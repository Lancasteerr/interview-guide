package interview.guide.modules.voiceinterview.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.SessionMetaDTO;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.entity.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 负责语音面试会话创建、生命周期转换、列表查询和过期清理。
 */
@Slf4j
final class VoiceInterviewSessionLifecycleService {

    private static final String DEFAULT_USER_ID = "default";

    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceInterviewEvaluationRepository evaluationRepository;
    private final VoiceInterviewProperties properties;
    private final VoiceInterviewSessionCacheService sessionCacheService;
    private final VoiceInterviewPhaseService phaseService;
    private final VoiceInterviewMessageService messageService;
    private final VoiceInterviewEvaluationLifecycleService evaluationService;

    VoiceInterviewSessionLifecycleService(
        VoiceInterviewSessionRepository sessionRepository,
        VoiceInterviewEvaluationRepository evaluationRepository,
        VoiceInterviewProperties properties,
        VoiceInterviewSessionCacheService sessionCacheService,
        VoiceInterviewPhaseService phaseService,
        VoiceInterviewMessageService messageService,
        VoiceInterviewEvaluationLifecycleService evaluationService) {
        this.sessionRepository = sessionRepository;
        this.evaluationRepository = evaluationRepository;
        this.properties = properties;
        this.sessionCacheService = sessionCacheService;
        this.phaseService = phaseService;
        this.messageService = messageService;
        this.evaluationService = evaluationService;
    }

    SessionResponseDTO createSession(CreateSessionRequest request) {
        String effectiveSkillId = request.getSkillId() != null
            ? request.getSkillId() : InterviewDefaults.SKILL_ID;
        String effectiveLlmProvider = request.getLlmProvider() != null
            && !request.getLlmProvider().isBlank() ? request.getLlmProvider() : null;
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .userId(DEFAULT_USER_ID)
            .roleType(effectiveSkillId)
            .skillId(effectiveSkillId)
            .difficulty(request.getDifficulty() != null
                ? request.getDifficulty() : InterviewDefaults.DIFFICULTY)
            .customJdText(request.getCustomJdText())
            .resumeId(request.getResumeId())
            .introEnabled(request.getIntroEnabled())
            .techEnabled(request.getTechEnabled())
            .projectEnabled(request.getProjectEnabled())
            .hrEnabled(request.getHrEnabled())
            .llmProvider(effectiveLlmProvider)
            .plannedDuration(request.getPlannedDuration())
            .currentPhase(phaseService.determineFirstPhase(request))
            .build();

        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        sessionCacheService.put(saved);
        log.info("Created voice interview session: {} with template: {}, phase: {}",
            saved.getId(), effectiveSkillId, saved.getCurrentPhase());
        return buildSessionResponse(saved);
    }

    void endSessionIfInProgress(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong).orElse(null);
        if (session == null || session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            return;
        }
        log.info("Auto-ending IN_PROGRESS session {} after WebSocket disconnect", sessionId);
        endSession(session);
        evaluationService.sendTaskAfterCommit(sessionIdLong);
    }

    void endSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);
        if (session == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }
        endSession(session);
        evaluationService.sendTaskAfterCommit(sessionIdLong);
    }

    VoiceInterviewSessionEntity getSession(String sessionId) {
        return getSession(parseSessionId(sessionId));
    }

    VoiceInterviewSessionEntity getSession(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        VoiceInterviewSessionEntity cached = sessionCacheService.get(sessionId);
        if (cached != null) {
            log.debug("Session {} found in cache", sessionId);
            return cached;
        }
        return sessionRepository.findById(sessionId).orElse(null);
    }

    void startPhase(String sessionId, String phaseStr) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);
        if (session == null) {
            log.warn("Cannot start phase - session not found: {}", sessionId);
            return;
        }
        try {
            VoiceInterviewSessionEntity.InterviewPhase newPhase =
                VoiceInterviewSessionEntity.InterviewPhase.valueOf(phaseStr.toUpperCase());
            VoiceInterviewSessionEntity.InterviewPhase oldPhase = session.getCurrentPhase();
            session.setCurrentPhase(newPhase);
            sessionRepository.save(session);
            sessionCacheService.put(session);
            log.info("Session {} transitioned from phase {} to {}", sessionId, oldPhase, newPhase);
        } catch (IllegalArgumentException e) {
            log.error("Invalid phase string: {}", phaseStr, e);
        }
    }

    VoiceInterviewSessionEntity.InterviewPhase getCurrentPhase(String sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);
        return session != null ? session.getCurrentPhase() : null;
    }

    void pauseSession(String sessionId, String reason) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
        if (session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "会话状态为 " + session.getStatus() + "，无法暂停");
        }
        session.setStatus(VoiceInterviewSessionStatus.PAUSED);
        session.setPausedAt(LocalDateTime.now());
        sessionRepository.save(session);
        sessionCacheService.invalidate(sessionIdLong);
        log.info("Session {} paused, reason: {}", sessionId, reason);
    }

    SessionResponseDTO resumeSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
        if (session.getStatus() != VoiceInterviewSessionStatus.PAUSED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "会话状态为 " + session.getStatus() + "，无法恢复");
        }
        session.setStatus(VoiceInterviewSessionStatus.IN_PROGRESS);
        session.setResumedAt(LocalDateTime.now());
        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        sessionCacheService.put(saved);
        log.info("Session {} resumed with {} messages in conversation history",
            sessionId, countDialogueMessages(sessionIdLong));
        return buildSessionResponse(saved);
    }

    List<SessionMetaDTO> getAllSessions(String userId, String status) {
        String effectiveUserId = userId != null ? userId : DEFAULT_USER_ID;
        List<VoiceInterviewSessionEntity> sessions;
        if (status != null && !status.isEmpty()) {
            VoiceInterviewSessionStatus statusEnum =
                VoiceInterviewSessionStatus.valueOf(status.toUpperCase());
            sessions = sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(
                effectiveUserId, statusEnum);
        } else {
            sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(effectiveUserId);
        }
        return sessions.stream()
            .map(session -> SessionMetaDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .status(session.getStatus().name())
                .currentPhase(session.getCurrentPhase().name())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .actualDuration(session.getActualDuration())
                .messageCount(countDialogueMessages(session.getId()))
                .evaluateStatus(session.getEvaluateStatus() != null
                    ? session.getEvaluateStatus().name() : null)
                .evaluateError(session.getEvaluateError())
                .build())
            .collect(Collectors.toList());
    }

    SessionResponseDTO getSessionDTO(Long sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);
        return session == null ? null : buildSessionResponse(session);
    }

    boolean shouldTransitionToNextPhase(
        VoiceInterviewSessionEntity session, LocalDateTime phaseStartTime, int questionCount) {
        return phaseService.shouldTransitionToNextPhase(session, phaseStartTime, questionCount);
    }

    VoiceInterviewSessionEntity.InterviewPhase getNextPhase(VoiceInterviewSessionEntity session) {
        return phaseService.getNextPhase(session);
    }

    void deleteSession(Long sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }
        evaluationRepository.findBySessionId(sessionId).ifPresent(evaluationRepository::delete);
        messageService.deleteMessages(sessionId);
        sessionRepository.deleteById(sessionId);
        log.info("Deleted voice interview session: {}", sessionId);
    }

    int cleanupStaleSessions() {
        LocalDateTime staleThreshold = LocalDateTime.now().minusHours(2);
        List<VoiceInterviewSessionEntity> staleSessions = sessionRepository
            .findByStatusAndStartTimeBefore(VoiceInterviewSessionStatus.IN_PROGRESS, staleThreshold);
        int cleaned = 0;
        for (VoiceInterviewSessionEntity session : staleSessions) {
            log.info("Cleaning up stale IN_PROGRESS session {}, started at {}",
                session.getId(), session.getStartTime());
            endSession(session);
            evaluationService.sendTaskAfterCommit(session.getId());
            cleaned++;
        }
        return cleaned + evaluationService.recoverStaleEvaluations();
    }

    private void endSession(VoiceInterviewSessionEntity session) {
        session.setEndTime(LocalDateTime.now());
        session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
        session.setStatus(VoiceInterviewSessionStatus.COMPLETED);
        session.setActualDuration((int) Duration.between(
            session.getStartTime(), LocalDateTime.now()).toSeconds());
        session.setEvaluateStatus(AsyncTaskStatus.PENDING);
        sessionRepository.save(session);
        sessionCacheService.invalidate(session.getId());
        log.info("Ended voice interview session: {}, duration: {} seconds, evaluation triggered",
            session.getId(), session.getActualDuration());
    }

    private SessionResponseDTO buildSessionResponse(VoiceInterviewSessionEntity session) {
        return SessionResponseDTO.builder()
            .sessionId(session.getId())
            .roleType(session.getRoleType())
            .currentPhase(session.getCurrentPhase().name())
            .status(session.getStatus().name())
            .startTime(session.getStartTime())
            .plannedDuration(session.getPlannedDuration())
            .webSocketUrl(String.format("/ws/voice-interview/%d", session.getId()))
            .build();
    }

    private long countDialogueMessages(Long sessionId) {
        return messageService.countDialogueMessages(sessionId);
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
