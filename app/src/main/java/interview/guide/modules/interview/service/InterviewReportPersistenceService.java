package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.dto.InterviewReportDTO;
import interview.guide.modules.interview.entity.InterviewAnswerEntity;
import interview.guide.modules.interview.entity.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 面试报告持久化适配器，负责报告和评测答案的批量写入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
class InterviewReportPersistenceService {

  private final InterviewSessionRepository sessionRepository;
  private final InterviewAnswerRepository answerRepository;
  private final ObjectMapper objectMapper;

  @Transactional(rollbackFor = Exception.class)
  void saveReport(String sessionId, InterviewReportDTO report) {
    try {
      InterviewSessionEntity session = sessionRepository.findBySessionId(sessionId).orElse(null);
      if (session == null) {
        log.warn("会话不存在: {}", sessionId);
        return;
      }

      session.setOverallScore(report.overallScore());
      session.setOverallFeedback(report.overallFeedback());
      session.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
      session.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
      session.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
      session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
      session.setCompletedAt(java.time.LocalDateTime.now());
      sessionRepository.save(session);

      List<InterviewAnswerEntity> existingAnswers =
          answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
      Map<Integer, InterviewAnswerEntity> answerMap = existingAnswers.stream()
          .collect(Collectors.toMap(InterviewAnswerEntity::getQuestionIndex,
              answer -> answer, (first, ignored) -> first));
      Map<Integer, InterviewReportDTO.ReferenceAnswer> referenceAnswerMap =
          report.referenceAnswers().stream()
              .collect(Collectors.toMap(InterviewReportDTO.ReferenceAnswer::questionIndex,
                  answer -> answer, (first, ignored) -> first));

      List<InterviewAnswerEntity> answersToSave = new ArrayList<>();
      for (InterviewReportDTO.QuestionEvaluation evaluation : report.questionDetails()) {
        InterviewAnswerEntity answer = answerMap.get(evaluation.questionIndex());
        if (answer == null) {
          answer = new InterviewAnswerEntity();
          answer.setSession(session);
          answer.setQuestionIndex(evaluation.questionIndex());
          answer.setQuestion(evaluation.question());
          answer.setCategory(evaluation.category());
          answer.setUserAnswer(null);
          log.debug("为未回答的题目 {} 创建答案记录", evaluation.questionIndex());
        }

        answer.setScore(evaluation.score());
        answer.setFeedback(evaluation.feedback());

        InterviewReportDTO.ReferenceAnswer referenceAnswer =
            referenceAnswerMap.get(evaluation.questionIndex());
        if (referenceAnswer != null) {
          answer.setReferenceAnswer(referenceAnswer.referenceAnswer());
          if (referenceAnswer.keyPoints() != null && !referenceAnswer.keyPoints().isEmpty()) {
            answer.setKeyPointsJson(objectMapper.writeValueAsString(referenceAnswer.keyPoints()));
          }
        }
        answersToSave.add(answer);
      }

      answerRepository.saveAll(answersToSave);
      log.info("面试报告已保存: sessionId={}, score={}, 答案数={}",
          sessionId, report.overallScore(), answersToSave.size());
    } catch (JacksonException e) {
      log.error("序列化报告失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存报告失败");
    }
  }
}
