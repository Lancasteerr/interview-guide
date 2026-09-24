package interview.guide.modules.interview.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.dto.CreateInterviewRequest;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.dto.InterviewQuestionDTO;
import interview.guide.modules.interview.dto.InterviewReportDTO;
import interview.guide.modules.interview.dto.InterviewSessionDTO;
import interview.guide.modules.interview.entity.InterviewSessionEntity;
import interview.guide.modules.interview.dto.SubmitAnswerRequest;
import interview.guide.modules.interview.dto.SubmitAnswerResponse;
import interview.guide.modules.interview.dto.InterviewSessionDTO.SessionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 面试会话管理服务
 * 管理面试会话的生命周期，使用 Redis 缓存会话状态
 */
@Slf4j
@Service
public class InterviewSessionService {

    private static final String CREATE_LOCK_PREFIX = "interview:create:";
    private static final String CREATE_RESULT_PREFIX = "interview:create:result:";
    private static final Duration CREATE_RESULT_TTL = Duration.ofDays(1);

    private final InterviewQuestionService questionService;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;
    private final LlmProviderRegistry llmProviderRegistry;
    private final RedisService redisService;
    private final InterviewSessionStateService stateService;
    private final InterviewAnswerSubmissionService answerSubmissionService;

    public InterviewSessionService(
        InterviewQuestionService questionService,
        AnswerEvaluationService evaluationService,
        InterviewPersistenceService persistenceService,
        InterviewSessionCache sessionCache,
        ObjectMapper objectMapper,
        EvaluateStreamProducer evaluateStreamProducer,
        LlmProviderRegistry llmProviderRegistry,
        RedisService redisService) {
        this(
            questionService,
            evaluationService,
            persistenceService,
            sessionCache,
            objectMapper,
            evaluateStreamProducer,
            llmProviderRegistry,
            redisService,
            new InterviewSessionStateService(persistenceService, sessionCache, objectMapper),
            new InterviewAnswerSubmissionService(
                persistenceService,
                sessionCache,
                objectMapper,
                evaluateStreamProducer,
                new InterviewSessionStateService(persistenceService, sessionCache, objectMapper)
            )
        );
    }

    @Autowired
    public InterviewSessionService(
        InterviewQuestionService questionService,
        AnswerEvaluationService evaluationService,
        InterviewPersistenceService persistenceService,
        InterviewSessionCache sessionCache,
        ObjectMapper objectMapper,
        EvaluateStreamProducer evaluateStreamProducer,
        LlmProviderRegistry llmProviderRegistry,
        RedisService redisService,
        InterviewSessionStateService stateService,
        InterviewAnswerSubmissionService answerSubmissionService) {
        this.questionService = questionService;
        this.evaluationService = evaluationService;
        this.persistenceService = persistenceService;
        this.sessionCache = sessionCache;
        this.objectMapper = objectMapper;
        this.llmProviderRegistry = llmProviderRegistry;
        this.redisService = redisService;
        this.stateService = stateService;
        this.answerSubmissionService = answerSubmissionService;
    }

    /**
     * 创建新的面试会话
     * 注意：如果已有未完成的会话，不会创建新的，而是返回现有会话
     * 前端应该先调用 findUnfinishedSession 检查，或者使用 forceCreate 参数强制创建
     */
    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        String requestId = normalizeRequestId(request.requestId());
        if (requestId == null) {
            return createSessionInternal(request);
        }

        return redisService.executeWithLock(
            CREATE_LOCK_PREFIX + requestId,
            185,
            600,
            TimeUnit.SECONDS,
            () -> createIdempotentSession(request, requestId)
        );
    }

    private InterviewSessionDTO createIdempotentSession(CreateInterviewRequest request, String requestId) {
        String resultKey = CREATE_RESULT_PREFIX + requestId;
        String cachedSessionId = redisService.get(resultKey);
        if (cachedSessionId != null) {
            log.info("复用缓存中的幂等创建请求: requestId={}, sessionId={}", requestId, cachedSessionId);
            return getSession(cachedSessionId);
        }

        Optional<InterviewSessionEntity> existing = persistenceService.findByRequestId(requestId);
        if (existing.isPresent()) {
            String existingSessionId = existing.get().getSessionId();
            log.info("从数据库恢复幂等创建请求: requestId={}, sessionId={}",
                requestId, existingSessionId);
            redisService.set(resultKey, existingSessionId, CREATE_RESULT_TTL);
            return getSession(existingSessionId);
        }

        InterviewSessionDTO created = createSessionInternal(request, requestId);
        redisService.set(resultKey, created.sessionId(), CREATE_RESULT_TTL);
        return created;
    }

    private InterviewSessionDTO createSessionInternal(CreateInterviewRequest request) {
        return createSessionInternal(request, null);
    }

    private InterviewSessionDTO createSessionInternal(CreateInterviewRequest request, String requestId) {
        // 如果指定了resumeId且未强制创建，检查是否有未完成的会话
        if (request.resumeId() != null && !Boolean.TRUE.equals(request.forceCreate())) {
            Optional<InterviewSessionDTO> unfinishedOpt = findUnfinishedSession(request.resumeId());
            if (unfinishedOpt.isPresent()) {
                log.info("检测到未完成的面试会话，返回现有会话: resumeId={}, sessionId={}",
                    request.resumeId(), unfinishedOpt.get().sessionId());
                return unfinishedOpt.get();
            }
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String skillId = request.skillId() != null ? request.skillId() : InterviewDefaults.SKILL_ID;
        String difficulty = request.difficulty() != null ? request.difficulty() : InterviewDefaults.DIFFICULTY;

        log.info("创建新面试会话: {}, skill: {}, difficulty: {}, questionCount: {}, resumeId: {}",
            sessionId, skillId, difficulty, request.questionCount(), request.resumeId());

        // 获取历史问题（通用模式按 skillId 查询，有简历时按 resumeId + skillId 精确匹配）
        List<HistoricalQuestion> historicalQuestions =
            persistenceService.getHistoricalQuestions(skillId, request.resumeId());

        // 基于 Skill 生成面试问题
        List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
            request.llmProvider(),
            skillId,
            difficulty,
            request.resumeText(),
            request.questionCount(),
            historicalQuestions,
            request.customCategories(),
            request.jdText()
        );

        if (requestId != null) {
            try {
                persistenceService.saveIdempotentSession(
                    sessionId,
                    request.resumeId(),
                    questions.size(),
                    questions,
                    request.llmProvider(),
                    skillId,
                    difficulty,
                    requestId
                );
            } catch (Exception e) {
                Optional<InterviewSessionEntity> concurrentlyCreated =
                    persistenceService.findByRequestId(requestId);
                if (concurrentlyCreated.isPresent()) {
                    return getSession(concurrentlyCreated.get().getSessionId());
                }
                log.error("持久化幂等面试会话失败: requestId={}", requestId, e);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "创建面试会话失败，请重试");
            }
        } else {
            try {
                persistenceService.saveSession(sessionId, request.resumeId(),
                    questions.size(), questions, request.llmProvider(), skillId, difficulty);
            } catch (Exception e) {
                log.warn("保存面试会话到数据库失败: {}", e.getMessage());
            }
        }

        // 幂等请求必须先成功落库，再写入易失缓存，保证进程异常后可从数据库恢复。
        sessionCache.saveSession(
            sessionId,
            request.resumeText() != null ? request.resumeText() : "",
            request.resumeId(),
            null,
            null,
            questions,
            0,
            SessionStatus.CREATED
        );

        return new InterviewSessionDTO(
            sessionId,
            request.resumeText() != null ? request.resumeText() : "",
            questions.size(),
            0,
            questions,
            SessionStatus.CREATED,
            null,
            null
        );
    }

    public InterviewSessionDTO createSessionFromQuestions(List<InterviewQuestionDTO> questions,
                                                          String llmProvider,
                                                          String skillId,
                                                          String difficulty,
                                                          Long knowledgeBaseId,
                                                          String interviewCategory) {
        if (questions == null || questions.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "面试题目不能为空");
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        persistenceService.saveSession(
            sessionId, null, questions.size(), questions, llmProvider, skillId, difficulty,
            "KNOWLEDGE_BASE", knowledgeBaseId, interviewCategory);
        sessionCache.saveSession(sessionId, "", null, knowledgeBaseId, interviewCategory,
            questions, 0, SessionStatus.CREATED);

        return new InterviewSessionDTO(
            sessionId,
            "",
            questions.size(),
            0,
            questions,
            SessionStatus.CREATED,
            knowledgeBaseId,
            interviewCategory
        );
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }

        String normalized = requestId.trim();
        if (!normalized.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "requestId 格式不正确");
        }
        return normalized;
    }

    /**
     * 获取会话信息（优先从缓存获取，缓存未命中则从数据库恢复）
     */
    public InterviewSessionDTO getSession(String sessionId) {
        return stateService.getSession(sessionId);
    }

    /**
     * 查找并恢复未完成的面试会话
     */
    public Optional<InterviewSessionDTO> findUnfinishedSession(Long resumeId) {
        return stateService.findUnfinishedSession(resumeId);
    }

    /**
     * 查找并恢复未完成的面试会话，如果不存在则抛出异常
     */
    public InterviewSessionDTO findUnfinishedSessionOrThrow(Long resumeId) {
        return findUnfinishedSession(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "未找到未完成的面试会话"));
    }

    /**
     * 获取当前问题的响应（包含完成状态）
     */
    public Map<String, Object> getCurrentQuestionResponse(String sessionId) {
        InterviewQuestionDTO question = getCurrentQuestion(sessionId);
        if (question == null) {
            return Map.of(
                "completed", true,
                "message", "所有问题已回答完毕"
            );
        }
        return Map.of(
            "completed", false,
            "question", question
        );
    }

    /**
     * 获取当前问题
     */
    public InterviewQuestionDTO getCurrentQuestion(String sessionId) {
        CachedSession session = stateService.getOrRestoreSession(sessionId);
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        if (session.getCurrentIndex() >= questions.size()) {
            return null; // 所有问题已回答完
        }

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            session.setStatus(SessionStatus.IN_PROGRESS);
            sessionCache.updateSessionStatus(sessionId, SessionStatus.IN_PROGRESS);

            // 同步到数据库
            try {
                persistenceService.updateSessionStatus(sessionId,
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            } catch (Exception e) {
                log.warn("更新会话状态失败: {}", e.getMessage());
            }
        }

        return questions.get(session.getCurrentIndex());
    }

    /**
     * 提交答案（并进入下一题）
     * 如果是最后一题，自动触发异步评估
     */
    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        return answerSubmissionService.submitAnswer(request);
    }

    /**
     * 暂存答案（不进入下一题）
     */
    public void saveAnswer(SubmitAnswerRequest request) {
        answerSubmissionService.saveAnswer(request);
    }

    /**
     * 提前交卷（触发异步评估）
     */
    public void completeInterview(String sessionId) {
        answerSubmissionService.completeInterview(sessionId);
    }

    /**
     * 生成评估报告
     */
    public InterviewReportDTO generateReport(String sessionId) {
        CachedSession session = stateService.getOrRestoreSession(sessionId);

        if (session.getStatus() != SessionStatus.COMPLETED && session.getStatus() != SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, "面试尚未完成，无法生成报告");
        }

        log.info("生成面试报告: {}", sessionId);

        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        // 获取 LLM 客户端
        String provider = null;
        Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
        if (entityOpt.isPresent()) {
            provider = entityOpt.get().getLlmProvider();
        }
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

        InterviewReportDTO report = evaluationService.evaluateInterview(
            chatClient,
            sessionId,
            session.getResumeText(),
            questions
        );

        // 更新 Redis 缓存状态
        sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);

        // 保存报告到数据库
        try {
            persistenceService.saveReport(sessionId, report);
        } catch (Exception e) {
            log.warn("保存报告到数据库失败: {}", e.getMessage());
        }

        return report;
    }

}
