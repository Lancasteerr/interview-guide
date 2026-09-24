package interview.guide.common.evaluation;

import interview.guide.common.evaluation.EvaluationReport.CategoryScore;
import interview.guide.common.evaluation.EvaluationReport.QuestionEvaluation;
import interview.guide.common.evaluation.EvaluationReport.ReferenceAnswer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评估结果组装器，负责批次结果合并、汇总输入准备和最终报告构建。
 */
final class EvaluationReportAssembler {

  List<UnifiedEvaluationService.QuestionEvalDTO> mergeQuestionEvaluations(
      List<UnifiedEvaluationService.BatchResult> batchResults) {
    List<UnifiedEvaluationService.QuestionEvalDTO> merged = new ArrayList<>();
    for (UnifiedEvaluationService.BatchResult result : batchResults) {
      List<Integer> expectedIndexes = result.questionIndexes();
      List<UnifiedEvaluationService.QuestionEvalDTO> current =
          result.report() != null && result.report().questionEvaluations() != null
              ? result.report().questionEvaluations() : List.of();

      Map<Integer, UnifiedEvaluationService.QuestionEvalDTO> byIndex = new HashMap<>();
      Set<Integer> returnedIndexes = new LinkedHashSet<>();
      for (UnifiedEvaluationService.QuestionEvalDTO dto : current) {
        if (dto == null) {
          continue;
        }
        returnedIndexes.add(dto.questionIndex());
        if (expectedIndexes.contains(dto.questionIndex())) {
          byIndex.putIfAbsent(dto.questionIndex(), dto);
        }
      }
      boolean countsMatch = current.size() == expectedIndexes.size();
      boolean allMapped = byIndex.size() == expectedIndexes.size();
      boolean oneBasedShift = !allMapped && countsMatch
          && isConsistentOneBasedShift(returnedIndexes, expectedIndexes);
      boolean noUsableIndex = byIndex.isEmpty() && countsMatch;
      boolean positionalFallback = oneBasedShift || noUsableIndex;

      for (int i = 0; i < expectedIndexes.size(); i++) {
        int originalIndex = expectedIndexes.get(i);
        UnifiedEvaluationService.QuestionEvalDTO dto = positionalFallback
            ? current.get(i) : byIndex.get(originalIndex);
        if (dto != null && dto.questionIndex() != originalIndex) {
          dto = new UnifiedEvaluationService.QuestionEvalDTO(originalIndex, dto.score(),
              dto.feedback(), dto.referenceAnswer(), dto.keyPoints());
        }
        merged.add(dto != null ? dto : new UnifiedEvaluationService.QuestionEvalDTO(
            originalIndex, 0, "该题未成功生成评估结果，系统按 0 分处理。", "", List.of()));
      }
    }
    return merged;
  }

  String mergeOverallFeedback(List<UnifiedEvaluationService.BatchResult> batchResults) {
    String feedback = batchResults.stream()
        .map(UnifiedEvaluationService.BatchResult::report)
        .filter(report -> report != null && report.overallFeedback() != null
            && !report.overallFeedback().isBlank())
        .map(UnifiedEvaluationService.BatchReportDTO::overallFeedback)
        .collect(Collectors.joining("\n\n"));
    return feedback.isBlank() ? "本次面试已完成分批评估，但未生成有效综合评语。" : feedback;
  }

  List<String> mergeListItems(List<UnifiedEvaluationService.BatchResult> batchResults,
      boolean strengthsMode) {
    Set<String> merged = new LinkedHashSet<>();
    for (UnifiedEvaluationService.BatchResult result : batchResults) {
      UnifiedEvaluationService.BatchReportDTO report = result.report();
      if (report == null) {
        continue;
      }
      List<String> items = strengthsMode ? report.strengths() : report.improvements();
      if (items == null) {
        continue;
      }
      items.stream()
          .filter(item -> item != null && !item.isBlank())
          .map(String::trim)
          .forEach(merged::add);
    }
    return merged.stream().limit(8).toList();
  }

  UnifiedEvaluationService.SummaryDTO summarize(
      List<QaRecord> qaRecords, List<UnifiedEvaluationService.QuestionEvalDTO> evaluations,
      UnifiedEvaluationService.SummaryDTO summary, String fallbackFeedback,
      List<String> fallbackStrengths, List<String> fallbackImprovements) {
    String feedback = summary != null && summary.overallFeedback() != null
        && !summary.overallFeedback().isBlank()
        ? summary.overallFeedback() : fallbackFeedback;
    List<String> strengths = sanitizeItems(summary != null ? summary.strengths() : null,
        fallbackStrengths);
    List<String> improvements = sanitizeItems(summary != null ? summary.improvements() : null,
        fallbackImprovements);
    return new UnifiedEvaluationService.SummaryDTO(feedback, strengths, improvements);
  }

  EvaluationReport buildReport(String sessionId, List<QaRecord> qaRecords,
      List<UnifiedEvaluationService.QuestionEvalDTO> evaluations, String overallFeedback,
      List<String> strengths, List<String> improvements) {
    List<QuestionEvaluation> questionDetails = new ArrayList<>();
    List<ReferenceAnswer> referenceAnswers = new ArrayList<>();
    Map<String, List<Integer>> categoryScoresMap = new HashMap<>();
    long answeredCount = qaRecords.stream()
        .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank())
        .count();
    int evalSize = evaluations != null ? evaluations.size() : 0;

    for (int i = 0; i < qaRecords.size(); i++) {
      QaRecord question = qaRecords.get(i);
      UnifiedEvaluationService.QuestionEvalDTO eval = i < evalSize ? evaluations.get(i) : null;
      boolean hasAnswer = question.userAnswer() != null && !question.userAnswer().isBlank();
      int score = hasAnswer && eval != null ? eval.score() : 0;
      String feedback = eval != null && eval.feedback() != null
          ? eval.feedback() : "该题未成功生成评估反馈。";
      String referenceAnswer = eval != null && eval.referenceAnswer() != null
          ? eval.referenceAnswer() : "";
      List<String> keyPoints = eval != null && eval.keyPoints() != null
          ? eval.keyPoints() : List.of();

      questionDetails.add(new QuestionEvaluation(question.questionIndex(), question.question(),
          question.category(), question.userAnswer(), score, feedback));
      referenceAnswers.add(new ReferenceAnswer(question.questionIndex(), question.question(),
          referenceAnswer, keyPoints));
      categoryScoresMap.computeIfAbsent(question.category(), key -> new ArrayList<>()).add(score);
    }

    List<CategoryScore> categoryScores = categoryScoresMap.entrySet().stream()
        .map(entry -> new CategoryScore(entry.getKey(),
            (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
            entry.getValue().size()))
        .toList();
    int overallScore = answeredCount == 0 ? 0
        : (int) questionDetails.stream().mapToInt(QuestionEvaluation::score).average().orElse(0);

    return new EvaluationReport(sessionId, qaRecords.size(), overallScore, categoryScores,
        questionDetails, overallFeedback, strengths != null ? strengths : List.of(),
        improvements != null ? improvements : List.of(), referenceAnswers);
  }

  String buildCategorySummary(List<QaRecord> qaRecords,
      List<UnifiedEvaluationService.QuestionEvalDTO> evaluations) {
    Map<String, List<Integer>> categoryScores = new HashMap<>();
    for (int i = 0; i < qaRecords.size(); i++) {
      QaRecord question = qaRecords.get(i);
      UnifiedEvaluationService.QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
      int score = eval != null && question.userAnswer() != null && !question.userAnswer().isBlank()
          ? eval.score() : 0;
      categoryScores.computeIfAbsent(question.category(), key -> new ArrayList<>()).add(score);
    }
    return categoryScores.entrySet().stream()
        .map(entry -> String.format("- %s: 平均分 %d, 题数 %d", entry.getKey(),
            (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
            entry.getValue().size()))
        .sorted()
        .collect(Collectors.joining("\n"));
  }

  String buildQuestionHighlights(List<QaRecord> qaRecords,
      List<UnifiedEvaluationService.QuestionEvalDTO> evaluations) {
    List<String> highlights = new ArrayList<>();
    for (int i = 0; i < qaRecords.size(); i++) {
      QaRecord question = qaRecords.get(i);
      UnifiedEvaluationService.QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
      int score = eval != null ? eval.score() : 0;
      String feedback = eval != null && eval.feedback() != null ? eval.feedback() : "";
      String shortQuestion = question.question().length() > 50
          ? question.question().substring(0, 50) + "..." : question.question();
      String shortFeedback = feedback.length() > 80
          ? feedback.substring(0, 80) + "..." : feedback;
      highlights.add(String.format("- Q%d | %s | 分数:%d | 反馈:%s",
          question.questionIndex() + 1, shortQuestion, score, shortFeedback));
    }
    return highlights.stream().limit(20).collect(Collectors.joining("\n"));
  }

  private List<String> sanitizeItems(List<String> primary, List<String> fallback) {
    List<String> source = primary != null && !primary.isEmpty() ? primary : fallback;
    if (source == null || source.isEmpty()) {
      return List.of();
    }
    return source.stream().filter(item -> item != null && !item.isBlank())
        .map(String::trim).distinct().limit(8).toList();
  }

  private boolean isConsistentOneBasedShift(Set<Integer> returnedIndexes,
      List<Integer> expectedIndexes) {
    Set<Integer> shifted = expectedIndexes.stream().map(index -> index + 1)
        .collect(Collectors.toSet());
    return returnedIndexes.equals(shifted);
  }
}
