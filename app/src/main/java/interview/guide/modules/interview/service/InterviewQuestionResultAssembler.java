package interview.guide.modules.interview.service;

import interview.guide.modules.interview.dto.InterviewQuestionDTO;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.service.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.service.InterviewSkillService.SkillDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试题目结果组装器，负责结构化结果转换、合并、截断和降级题目生成。
 */
final class InterviewQuestionResultAssembler {

  private static final Logger log = LoggerFactory.getLogger(InterviewQuestionResultAssembler.class);
  private static final String DEFAULT_QUESTION_TYPE = "GENERAL";
  private static final String[][] GENERIC_FALLBACK_QUESTIONS = {
      {"请描述一个你主导解决的技术难题，你的分析思路是什么？", "GENERAL", "综合能力"},
      {"你在做技术方案选型时，通常考虑哪些因素？请举例说明。", "GENERAL", "综合能力"},
      {"请分享一次你处理线上故障的经历，从发现到修复的完整过程。", "GENERAL", "综合能力"},
      {"你如何保证代码质量？介绍你实践过的有效手段。", "GENERAL", "综合能力"},
      {"描述一个你做过的技术优化案例，优化的动机、方案和效果。", "GENERAL", "综合能力"},
      {"你在团队协作中遇到过最大的分歧是什么？如何解决的？", "GENERAL", "综合能力"},
  };

  private final int followUpCount;

  InterviewQuestionResultAssembler(int followUpCount) {
    this.followUpCount = followUpCount;
  }

  List<InterviewQuestionDTO> convert(InterviewQuestionService.QuestionListDTO dto) {
    List<InterviewQuestionDTO> questions = new ArrayList<>();
    int index = 0;

    if (dto == null || dto.questions() == null) {
      return questions;
    }

    for (InterviewQuestionService.QuestionDTO question : dto.questions()) {
      if (question == null || question.question() == null || question.question().isBlank()) {
        continue;
      }
      String type = question.type() != null && !question.type().isBlank()
          ? question.type().toUpperCase() : DEFAULT_QUESTION_TYPE;
      int mainQuestionIndex = index;
      questions.add(InterviewQuestionDTO.create(index++, question.question(), type,
          question.category(), question.topicSummary(), false, null));

      List<String> followUps = sanitizeFollowUps(question.followUps());
      for (int i = 0; i < followUps.size(); i++) {
        questions.add(InterviewQuestionDTO.create(
            index++, followUps.get(i), type,
            buildFollowUpCategory(question.category(), i + 1), null, true, mainQuestionIndex));
      }
    }

    return questions;
  }

  List<InterviewQuestionDTO> capToMainCount(List<InterviewQuestionDTO> questions, int maxMainCount) {
    long currentMainCount = questions.stream().filter(question -> !question.isFollowUp()).count();
    if (currentMainCount <= maxMainCount) {
      if (currentMainCount < maxMainCount) {
        log.warn("AI 生成主问题不足: 请求={}, 实际={}", maxMainCount, currentMainCount);
      }
      return questions;
    }

    List<InterviewQuestionDTO> capped = new ArrayList<>();
    int mainSeen = 0;
    for (InterviewQuestionDTO question : questions) {
      if (!question.isFollowUp()) {
        mainSeen++;
      }
      if (mainSeen > maxMainCount) {
        break;
      }
      capped.add(question);
    }
    log.info("题目截断: 主问题 {} → {}", currentMainCount, maxMainCount);
    return capped;
  }

  List<InterviewQuestionDTO> merge(List<InterviewQuestionDTO> first,
      List<InterviewQuestionDTO> second) {
    if (second.isEmpty()) {
      return first;
    }
    if (first.isEmpty()) {
      return second;
    }

    int offset = first.size();
    List<InterviewQuestionDTO> merged = new ArrayList<>(first);
    for (InterviewQuestionDTO question : second) {
      int newIndex = question.questionIndex() + offset;
      Integer newParent = question.parentQuestionIndex() != null
          ? question.parentQuestionIndex() + offset : null;
      merged.add(InterviewQuestionDTO.create(
          newIndex, question.question(), question.type(), question.category(),
          question.topicSummary(), question.isFollowUp(), newParent));
    }
    return merged;
  }

  List<InterviewQuestionDTO> fallback(SkillDTO skill, int count) {
    List<SkillCategoryDTO> categories = skill != null ? skill.categories() : List.of();
    List<InterviewQuestionDTO> questions = new ArrayList<>();
    int index = 0;

    if (!categories.isEmpty()) {
      int generated = 0;
      while (generated < count) {
        SkillCategoryDTO category = categories.get(generated % categories.size());
        String question = "请谈谈你在\"" + category.label() + "\"方向的技术理解和实践经验。";
        questions.add(InterviewQuestionDTO.create(index++, question, category.key(),
            category.label(), null, false, null));
        int mainIndex = index - 1;
        addDefaultFollowUps(questions, index, mainIndex, question, category.key(), category.label());
        index = questions.size();
        generated++;
      }
      return questions;
    }

    for (int i = 0; i < Math.min(count, GENERIC_FALLBACK_QUESTIONS.length); i++) {
      String[] fallbackQuestion = GENERIC_FALLBACK_QUESTIONS[i];
      questions.add(InterviewQuestionDTO.create(index++, fallbackQuestion[0], fallbackQuestion[1],
          fallbackQuestion[2], null, false, null));
      int mainIndex = index - 1;
      addDefaultFollowUps(questions, index, mainIndex, fallbackQuestion[0], fallbackQuestion[1],
          fallbackQuestion[2]);
      index = questions.size();
    }
    return questions;
  }

  String buildHistoricalSection(List<HistoricalQuestion> historicalQuestions) {
    if (historicalQuestions == null || historicalQuestions.isEmpty()) {
      return "暂无历史提问";
    }

    Map<String, List<String>> grouped = new HashMap<>();
    for (HistoricalQuestion historicalQuestion : historicalQuestions) {
      String type = historicalQuestion.type() != null && !historicalQuestion.type().isBlank()
          ? historicalQuestion.type() : DEFAULT_QUESTION_TYPE;
      String summary = historicalQuestion.topicSummary();
      if (summary == null || summary.isBlank()) {
        String question = historicalQuestion.question();
        summary = question.length() > 30 ? question.substring(0, 30) + "…" : question;
      }
      grouped.computeIfAbsent(type, key -> new ArrayList<>()).add(summary);
    }

    StringBuilder result = new StringBuilder("已考过的知识点（避免重复出题）：\n");
    for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
      result.append("- ").append(entry.getKey()).append(": ");
      result.append(String.join(", ", entry.getValue())).append('\n');
    }
    return result.toString();
  }

  private void addDefaultFollowUps(List<InterviewQuestionDTO> questions, int index, int mainIndex,
      String question, String type, String category) {
    for (int i = 0; i < followUpCount; i++) {
      questions.add(InterviewQuestionDTO.create(
          index + i, buildDefaultFollowUp(question, i + 1), type,
          buildFollowUpCategory(category, i + 1), null, true, mainIndex));
    }
  }

  private List<String> sanitizeFollowUps(List<String> followUps) {
    if (followUpCount == 0 || followUps == null || followUps.isEmpty()) {
      return List.of();
    }
    return followUps.stream()
        .filter(item -> item != null && !item.isBlank())
        .map(String::trim)
        .limit(followUpCount)
        .toList();
  }

  private String buildFollowUpCategory(String category, int order) {
    String base = category == null || category.isBlank() ? "追问" : category;
    return base + "（追问" + order + "）";
  }

  private String buildDefaultFollowUp(String mainQuestion, int order) {
    if (order == 1) {
      return "基于\"" + mainQuestion + "\"，请结合你亲自做过的一个真实场景展开说明。";
    }
    return "基于\"" + mainQuestion + "\"，如果线上出现异常，你会如何定位并给出修复方案？";
  }
}
