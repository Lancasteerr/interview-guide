package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.modules.interview.dto.InterviewQuestionDTO;
import interview.guide.modules.interview.dto.InterviewReportDTO;
import interview.guide.modules.interview.dto.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.entity.InterviewSessionEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * 负责面试报告生成、状态更新和报告持久化。
 */
@Component
@Slf4j
final class InterviewReportGenerationService {

    private final InterviewSessionStateService stateService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;
    private final LlmProviderRegistry llmProviderRegistry;
    private final AnswerEvaluationService evaluationService;

    InterviewReportGenerationService(
        InterviewSessionStateService stateService,
        InterviewPersistenceService persistenceService,
        InterviewSessionCache sessionCache,
        ObjectMapper objectMapper,
        LlmProviderRegistry llmProviderRegistry,
        AnswerEvaluationService evaluationService) {
        this.stateService = stateService;
        this.persistenceService = persistenceService;
        this.sessionCache = sessionCache;
        this.objectMapper = objectMapper;
        this.llmProviderRegistry = llmProviderRegistry;
        this.evaluationService = evaluationService;
    }

    InterviewReportDTO generateReport(String sessionId) {
        CachedSession session = stateService.getOrRestoreSession(sessionId);

        if (session.getStatus() != SessionStatus.COMPLETED && session.getStatus() != SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, "面试尚未完成，无法生成报告");
        }

        log.info("生成面试报告: {}", sessionId);

        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(findProvider(sessionId));
        InterviewReportDTO report = evaluationService.evaluateInterview(
            chatClient,
            sessionId,
            session.getResumeText(),
            questions
        );

        sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);
        saveReport(sessionId, report);
        return report;
    }

    private String findProvider(String sessionId) {
        Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
        return entityOpt.map(InterviewSessionEntity::getLlmProvider).orElse(null);
    }

    private void saveReport(String sessionId, InterviewReportDTO report) {
        try {
            persistenceService.saveReport(sessionId, report);
        } catch (Exception e) {
            log.warn("保存报告到数据库失败: {}", e.getMessage());
        }
    }
}
