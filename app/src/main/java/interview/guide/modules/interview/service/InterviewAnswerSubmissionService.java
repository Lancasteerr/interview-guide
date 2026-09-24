package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.modules.interview.dto.InterviewQuestionDTO;
import interview.guide.modules.interview.dto.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.dto.SubmitAnswerRequest;
import interview.guide.modules.interview.dto.SubmitAnswerResponse;
import interview.guide.modules.interview.entity.InterviewSessionEntity;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 负责面试答案暂存、提交、提前交卷和评测任务入队。
 */
@Component
@Slf4j
final class InterviewAnswerSubmissionService {

    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;
    private final EvaluateStreamProducer evaluateStreamProducer;
    private final InterviewSessionStateService stateService;

    InterviewAnswerSubmissionService(
        InterviewPersistenceService persistenceService,
        InterviewSessionCache sessionCache,
        ObjectMapper objectMapper,
        EvaluateStreamProducer evaluateStreamProducer,
        InterviewSessionStateService stateService) {
        this.persistenceService = persistenceService;
        this.sessionCache = sessionCache;
        this.objectMapper = objectMapper;
        this.evaluateStreamProducer = evaluateStreamProducer;
        this.stateService = stateService;
    }

    SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        CachedSession session = stateService.getOrRestoreSession(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        InterviewQuestionDTO question = questions.get(index);
        questions.set(index, question.withAnswer(request.answer()));

        int newIndex = index + 1;
        boolean hasNextQuestion = newIndex < questions.size();
        InterviewQuestionDTO nextQuestion = hasNextQuestion ? questions.get(newIndex) : null;
        SessionStatus newStatus = hasNextQuestion ? SessionStatus.IN_PROGRESS : SessionStatus.COMPLETED;

        persistSubmittedAnswer(request, index, question, newIndex, newStatus);

        sessionCache.updateQuestions(request.sessionId(), questions);
        sessionCache.updateCurrentIndex(request.sessionId(), newIndex);
        if (newStatus == SessionStatus.COMPLETED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.COMPLETED);
            enqueueEvaluationTask(request.sessionId());
        }

        log.info("会话 {} 提交答案: 问题{}, 剩余{}题",
            request.sessionId(), index, questions.size() - newIndex);

        return new SubmitAnswerResponse(
            hasNextQuestion,
            nextQuestion,
            newIndex,
            questions.size()
        );
    }

    void saveAnswer(SubmitAnswerRequest request) {
        CachedSession session = stateService.getOrRestoreSession(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        InterviewQuestionDTO question = questions.get(index);
        questions.set(index, question.withAnswer(request.answer()));
        sessionCache.updateQuestions(request.sessionId(), questions);

        if (session.getStatus() == SessionStatus.CREATED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.IN_PROGRESS);
        }

        try {
            persistenceService.saveAnswer(
                request.sessionId(), index,
                question.question(), question.category(),
                request.answer(), 0, null
            );
            persistenceService.updateSessionStatus(request.sessionId(),
                InterviewSessionEntity.SessionStatus.IN_PROGRESS);
        } catch (Exception e) {
            log.warn("暂存答案到数据库失败: {}", e.getMessage());
        }

        log.info("会话 {} 暂存答案: 问题{}", request.sessionId(), index);
    }

    void completeInterview(String sessionId) {
        CachedSession session = stateService.getOrRestoreSession(sessionId);
        if (session.getStatus() == SessionStatus.COMPLETED || session.getStatus() == SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }

        sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);

        try {
            persistenceService.updateSessionStatus(sessionId,
                InterviewSessionEntity.SessionStatus.COMPLETED);
            persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        } catch (Exception e) {
            log.warn("更新会话状态失败: {}", e.getMessage());
        }

        evaluateStreamProducer.sendEvaluateTask(sessionId);
        log.info("会话 {} 提前交卷，评估任务已入队", sessionId);
    }

    private void persistSubmittedAnswer(
        SubmitAnswerRequest request,
        int index,
        InterviewQuestionDTO question,
        int newIndex,
        SessionStatus newStatus) {
        try {
            persistenceService.saveAnswer(
                request.sessionId(), index,
                question.question(), question.category(),
                request.answer(), 0, null
            );
            persistenceService.updateCurrentQuestionIndex(request.sessionId(), newIndex);
            persistenceService.updateSessionStatus(
                request.sessionId(),
                newStatus == SessionStatus.COMPLETED
                    ? InterviewSessionEntity.SessionStatus.COMPLETED
                    : InterviewSessionEntity.SessionStatus.IN_PROGRESS
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("保存答案到数据库失败: sessionId={}, questionIndex={}",
                request.sessionId(), index, e);
            throw new BusinessException(ErrorCode.INTERVIEW_ANSWER_SAVE_FAILED,
                "保存答案失败，请稍后重试");
        }
    }

    private void enqueueEvaluationTask(String sessionId) {
        persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        evaluateStreamProducer.sendEvaluateTask(sessionId);
        log.info("会话 {} 已完成所有问题，评估任务已入队", sessionId);
    }
}
