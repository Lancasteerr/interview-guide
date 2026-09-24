package interview.guide.modules.interview.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.entity.InterviewAnswerEntity;
import interview.guide.modules.interview.dto.InterviewQuestionDTO;
import interview.guide.modules.interview.dto.InterviewReportDTO;
import interview.guide.modules.interview.entity.InterviewSessionEntity;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.entity.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 面试持久化服务
 * 面试会话和答案的持久化
 */
@Slf4j
@Service
public class InterviewPersistenceService {
    
    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final ResumeRepository resumeRepository;
    private final ObjectMapper objectMapper;
    private final InterviewReportPersistenceService reportPersistenceService;
    private final InterviewHistoryQuestionService historyQuestionService;

    @Autowired
    public InterviewPersistenceService(InterviewSessionRepository sessionRepository,
                                       InterviewAnswerRepository answerRepository,
                                       ResumeRepository resumeRepository,
                                       ObjectMapper objectMapper,
                                       InterviewReportPersistenceService reportPersistenceService,
                                       InterviewHistoryQuestionService historyQuestionService) {
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
        this.resumeRepository = resumeRepository;
        this.objectMapper = objectMapper;
        this.reportPersistenceService = reportPersistenceService;
        this.historyQuestionService = historyQuestionService;
    }

    /**
     * 保留测试和旧代码使用的四参数构造方式。
     */
    public InterviewPersistenceService(InterviewSessionRepository sessionRepository,
                                       InterviewAnswerRepository answerRepository,
                                       ResumeRepository resumeRepository,
                                       ObjectMapper objectMapper) {
        this(sessionRepository, answerRepository, resumeRepository, objectMapper,
            new InterviewReportPersistenceService(sessionRepository, answerRepository, objectMapper),
            new InterviewHistoryQuestionService(sessionRepository, objectMapper));
    }
    
    /**
     * 保存新的面试会话（支持可选简历）
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveSession(String sessionId, Long resumeId,
                                              int totalQuestions,
                                              List<InterviewQuestionDTO> questions,
                                              String llmProvider,
                                              String skillId,
                                              String difficulty) {
        return saveSession(sessionId, resumeId, totalQuestions, questions, llmProvider, skillId, difficulty,
            "NORMAL", null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveSession(String sessionId, Long resumeId,
                                              int totalQuestions,
                                              List<InterviewQuestionDTO> questions,
                                              String llmProvider,
                                              String skillId,
                                              String difficulty,
                                              String sourceType,
                                              Long knowledgeBaseId,
                                              String interviewCategory) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, sourceType, knowledgeBaseId, interviewCategory, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveIdempotentSession(String sessionId, Long resumeId,
                                                        int totalQuestions,
                                                        List<InterviewQuestionDTO> questions,
                                                        String llmProvider,
                                                        String skillId,
                                                        String difficulty,
                                                        String requestId) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, "NORMAL", null, null, requestId);
    }

    private InterviewSessionEntity saveSessionInternal(String sessionId, Long resumeId,
                                                       int totalQuestions,
                                                       List<InterviewQuestionDTO> questions,
                                                       String llmProvider,
                                                       String skillId,
                                                       String difficulty,
                                                       String sourceType,
                                                       Long knowledgeBaseId,
                                                       String interviewCategory,
                                                       String requestId) {
        try {
            InterviewSessionEntity session = new InterviewSessionEntity();
            session.setSessionId(sessionId);
            session.setRequestId(requestId);
            session.setTotalQuestions(totalQuestions);
            session.setCurrentQuestionIndex(0);
            session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
            session.setQuestionsJson(objectMapper.writeValueAsString(questions));
            session.setLlmProvider(llmProvider != null ? llmProvider : "default");
            session.setSkillId(skillId != null ? skillId : InterviewDefaults.SKILL_ID);
            session.setDifficulty(difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY);
            session.setSourceType(sourceType != null ? sourceType : "NORMAL");
            session.setKnowledgeBaseId(knowledgeBaseId);
            session.setInterviewCategory(interviewCategory);

            // 简历可选：有 resumeId 则关联简历
            if (resumeId != null) {
                Optional<ResumeEntity> resumeOpt = resumeRepository.findById(resumeId);
                resumeOpt.ifPresent(session::setResume);
            }

            InterviewSessionEntity saved = sessionRepository.save(session);
            log.info("面试会话已保存: sessionId={}, skillId={}, resumeId={}, sourceType={}",
                sessionId, skillId, resumeId, session.getSourceType());

            return saved;
        } catch (JacksonException e) {
            log.error("序列化问题列表失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存会话失败");
        }
    }
    
    /**
     * 更新会话状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSessionStatus(String sessionId, InterviewSessionEntity.SessionStatus status) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setStatus(status);
            if (status == InterviewSessionEntity.SessionStatus.COMPLETED ||
                status == InterviewSessionEntity.SessionStatus.EVALUATED) {
                session.setCompletedAt(LocalDateTime.now());
            }
            sessionRepository.save(session);
        }
    }

    /**
     * 更新评估状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            } else {
                session.setEvaluateError(null);
            }
            sessionRepository.save(session);
            log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
        }
    }
    
    /**
     * 更新当前问题索引
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentQuestionIndex(String sessionId, int index) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setCurrentQuestionIndex(index);
            session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            sessionRepository.save(session);
        }
    }
    
    /**
     * 保存面试答案
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewAnswerEntity saveAnswer(String sessionId, int questionIndex,
                                            String question, String category,
                                            String userAnswer, int score, String feedback) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewAnswerEntity answer = answerRepository
            .findBySession_SessionIdAndQuestionIndex(sessionId, questionIndex)
            .orElseGet(() -> {
                InterviewAnswerEntity created = new InterviewAnswerEntity();
                created.setSession(sessionOpt.get());
                created.setQuestionIndex(questionIndex);
                return created;
            });

        answer.setQuestion(question);
        answer.setCategory(category);
        answer.setUserAnswer(userAnswer);
        answer.setScore(score);
        answer.setFeedback(feedback);

        InterviewAnswerEntity saved = answerRepository.save(answer);
        log.info("面试答案已保存: sessionId={}, questionIndex={}, score={}", 
                sessionId, questionIndex, score);
        
        return saved;
    }
    
    /**
     * 保存面试报告
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveReport(String sessionId, InterviewReportDTO report) {
        reportPersistenceService.saveReport(sessionId, report);
    }
    
    /**
     * 根据会话ID获取会话
     */
    public Optional<InterviewSessionEntity> findBySessionId(String sessionId) {
        return sessionRepository.findBySessionId(sessionId);
    }

    public Optional<InterviewSessionEntity> findByRequestId(String requestId) {
        return sessionRepository.findByRequestId(requestId);
    }
    
    /**
     * 获取简历的所有面试记录
     */
    public List<InterviewSessionEntity> findByResumeId(Long resumeId) {
        return sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
    }

    /**
     * 获取所有面试记录（按创建时间倒序）
     */
    public List<InterviewSessionEntity> findAll() {
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }
    
    /**
     * 删除简历的所有面试会话
     * 由于InterviewSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionsByResumeId(Long resumeId) {
        List<InterviewSessionEntity> sessions = sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
        if (!sessions.isEmpty()) {
            sessionRepository.deleteAll(sessions);
            log.info("已删除 {} 个面试会话（包含所有答案）", sessions.size());
        }
    }
    
    /**
     * 删除单个面试会话
     * 由于InterviewSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionBySessionId(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            sessionRepository.delete(sessionOpt.get());
            log.info("已删除面试会话: sessionId={}", sessionId);
        } else {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }
    }
    
    /**
     * 查找未完成的面试会话（CREATED或IN_PROGRESS状态）
     */
    public Optional<InterviewSessionEntity> findUnfinishedSession(Long resumeId) {
        List<InterviewSessionEntity.SessionStatus> unfinishedStatuses = List.of(
            InterviewSessionEntity.SessionStatus.CREATED,
            InterviewSessionEntity.SessionStatus.IN_PROGRESS
        );
        return sessionRepository.findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(resumeId, unfinishedStatuses);
    }
    
    /**
     * 根据会话ID查找所有答案
     */
    public List<InterviewAnswerEntity> findAnswersBySessionId(String sessionId) {
        return answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
    }

    /**
     * 获取历史提问列表（结构化，按分类压缩用）。
     * 有 resumeId 时精确匹配 resumeId + skillId；无 resumeId 时按 skillId 查全部（通用模式兜底）。
     */
    public List<HistoricalQuestion> getHistoricalQuestions(String skillId, Long resumeId) {
        return historyQuestionService.getHistoricalQuestions(skillId, resumeId);
    }
}
