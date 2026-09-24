package interview.guide.modules.interview.service;

import interview.guide.modules.interview.dto.InterviewQuestionDTO;
import interview.guide.modules.interview.entity.InterviewSessionEntity;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 面试历史题目查询适配器，负责历史题目解析、去重和数量限制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
final class InterviewHistoryQuestionService {

  private static final int MAX_HISTORICAL_QUESTIONS = 60;

  private final InterviewSessionRepository sessionRepository;
  private final ObjectMapper objectMapper;

  List<HistoricalQuestion> getHistoricalQuestions(String skillId, Long resumeId) {
    List<InterviewSessionEntity> sessions = resumeId != null
        ? sessionRepository.findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(resumeId, skillId)
        : sessionRepository.findTop10BySkillIdOrderByCreatedAtDesc(skillId);

    log.info("加载历史题目: skillId={}, resumeId={}, 查到 {} 个历史会话",
        skillId, resumeId, sessions.size());

    LinkedHashSet<String> seen = new LinkedHashSet<>();
    List<HistoricalQuestion> result = sessions.stream()
        .map(InterviewSessionEntity::getQuestionsJson)
        .filter(json -> json != null && !json.isEmpty())
        .flatMap(json -> {
          try {
            List<InterviewQuestionDTO> questions = objectMapper.readValue(json,
                new TypeReference<List<InterviewQuestionDTO>>() {});
            return questions.stream()
                .filter(question -> !question.isFollowUp())
                .map(question -> new HistoricalQuestion(
                    question.question(), question.type(), question.topicSummary()));
          } catch (Exception e) {
            log.error("解析历史问题JSON失败", e);
            return java.util.stream.Stream.<HistoricalQuestion>empty();
          }
        })
        .filter(question -> seen.add(question.question()))
        .limit(MAX_HISTORICAL_QUESTIONS)
        .toList();

    log.info("历史题目加载完成: 去重后 {} 道主问题，按分类: {}", result.size(),
        result.stream().collect(java.util.stream.Collectors.groupingBy(
            question -> question.type() != null ? question.type() : "GENERAL",
            java.util.stream.Collectors.counting())));
    return result;
  }
}
