package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("统一评估服务分组装批测试")
class UnifiedEvaluationServiceTest {

  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;
  @Mock
  private ChatClient chatClient;

  private UnifiedEvaluationService service;
  private final List<String> batchPrompts = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    InterviewEvaluationProperties properties = new InterviewEvaluationProperties();
    service = new UnifiedEvaluationService(
        structuredOutputInvoker, new DefaultResourceLoader(), properties);
    batchPrompts.clear();
  }

  /**
   * 按批调用结构化输出：从 userPrompt 中解析本批 questionIndex，
   * 可定制每批返回的索引集合与分数
   */
  private void stubInvoker(java.util.function.IntFunction<Integer> scoreByIndex) {
    when(structuredOutputInvoker.invoke(
        any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any(Logger.class)))
    .thenAnswer(invocation -> {
      String userPrompt = invocation.getArgument(2);
      Object converter = invocation.getArgument(3);
      if (!userPrompt.contains("问答记录开始")) {
        return new Object(); // 汇总调用不会被读取，报告使用兜底
      }
      batchPrompts.add(extractQaSection(userPrompt));
      List<Integer> indexes = extractQuestionIndexes(userPrompt);
      List<UnifiedEvaluationService.QuestionEvalDTO> evals = indexes.stream()
          .filter(idx -> scoreByIndex.apply(idx) != null)
          .map(idx -> eval(
              idx, scoreByIndex.apply(idx)))
          .collect(Collectors.toList());
      return report(evals);
    });
  }

  private static UnifiedEvaluationService.QuestionEvalDTO eval(int index, int score) {
    return new UnifiedEvaluationService.QuestionEvalDTO(index, score, "反馈" + index, "参考" + index, List.of());
  }

  private static UnifiedEvaluationService.BatchReportDTO report(
      List<UnifiedEvaluationService.QuestionEvalDTO> evals) {
    return new UnifiedEvaluationService.BatchReportDTO(80, "批次评语", List.of(), List.of(), evals);
  }

  private static String extractQaSection(String prompt) {
    int start = prompt.indexOf("---问答记录开始---");
    int end = prompt.indexOf("---问答记录结束---");
    return start >= 0 && end > start ? prompt.substring(start, end) : prompt;
  }

  private static List<Integer> extractQuestionIndexes(String prompt) {
    Matcher matcher = Pattern.compile("(?m)^问题 questionIndex=(\\d+)").matcher(prompt);
    List<Integer> indexes = new ArrayList<>();
    while (matcher.find()) {
      int idx = Integer.parseInt(matcher.group(1));
      if (!indexes.contains(idx)) {
        indexes.add(idx);
      }
    }
    return indexes;
  }

  private List<Integer> batchSizes() {
    return batchPrompts.stream()
        .map(p -> extractQuestionIndexes(p).size())
        .toList();
  }

  private static interview.guide.common.evaluation.QaRecord question(int index, String category) {
    return new interview.guide.common.evaluation.QaRecord(index, "问题" + index, category, "回答" + index);
  }

  private static interview.guide.common.evaluation.QaRecord followUp(int index, int parent) {
    return new interview.guide.common.evaluation.QaRecord(index, "追问" + index, "Java 基础",
        "追问回答" + index, true, parent);
  }

  private static int scoreOf(EvaluationReport report, int questionIndex) {
    return report.questionDetails().stream()
        .filter(q -> q.questionIndex() == questionIndex)
        .findFirst().orElseThrow().score();
  }

  @Nested
  @DisplayName("分组与装批")
  class GroupingAndBatching {

    @Test
    @DisplayName("7 道独立题 + 1 主问题 + 2 追问：最后 3 题进入同一批")
    void shouldKeepFollowUpsWithParentInSameBatch() {
      stubInvoker(idx -> 80);
      List<interview.guide.common.evaluation.QaRecord> records = new ArrayList<>();
      for (int i = 0; i < 7; i++) {
        records.add(question(i, "Java 基础"));
      }
      records.add(question(7, "Redis"));
      records.add(followUp(8, 7));
      records.add(followUp(9, 7));

      service.evaluate(chatClient, "s1", records, null);

      assertThat(batchSizes()).containsExactly(7, 3);
      assertThat(batchPrompts.get(1)).contains("questionIndex=7");
      assertThat(batchPrompts.get(1)).contains("questionIndex=8");
      assertThat(batchPrompts.get(1)).contains("questionIndex=9");
      assertThat(batchPrompts.get(1)).contains("（追问，针对 questionIndex=7）");
      assertThat(batchPrompts.get(0)).doesNotContain("追问");
    }

    @Test
    @DisplayName("包含 10 道题的问答组独占一个超限批次")
    void shouldKeepOversizedGroupInSingleBatch() {
      stubInvoker(idx -> 80);
      List<interview.guide.common.evaluation.QaRecord> records = new ArrayList<>();
      records.add(question(0, "Java 基础"));
      for (int i = 1; i <= 9; i++) {
        records.add(followUp(i, 0));
      }

      service.evaluate(chatClient, "s2", records, null);

      // 10 题的组不可拆分，独占一个超限批次
      assertThat(batchSizes()).containsExactly(10);
    }

    @Test
    @DisplayName("追问父索引不存在时独立成组且不抛异常")
    void shouldDegradeFollowUpWithMissingParent() {
      stubInvoker(idx -> 80);
      List<interview.guide.common.evaluation.QaRecord> records = new ArrayList<>();
      for (int i = 0; i < 7; i++) {
        records.add(question(i, "Java 基础"));
      }
      records.add(followUp(7, 99));

      service.evaluate(chatClient, "s3", records, null);

      assertThat(batchSizes()).containsExactly(8);
    }

    @Test
    @DisplayName("两个主问题各自带追问时保持输入顺序")
    void shouldPreserveInputOrderForTwoGroups() {
      stubInvoker(idx -> 80);
      List<interview.guide.common.evaluation.QaRecord> records = List.of(
          question(0, "MySQL"), followUp(1, 0),
          question(2, "Redis"), followUp(3, 2));

      service.evaluate(chatClient, "s4", records, null);

      assertThat(batchSizes()).containsExactly(4);
      String prompt = batchPrompts.get(0);
      int idx0 = prompt.indexOf("questionIndex=0");
      int idx1 = prompt.indexOf("questionIndex=1");
      int idx2 = prompt.indexOf("questionIndex=2");
      int idx3 = prompt.indexOf("questionIndex=3");
      assertThat(idx0).isLessThan(idx1).isLessThan(idx2).isLessThan(idx3);
    }

    @Test
    @DisplayName("无关系字段时与固定分批行为兼容")
    void shouldBehaveLikeFixedBatchingWithoutRelations() {
      stubInvoker(idx -> 80);
      List<interview.guide.common.evaluation.QaRecord> records = new ArrayList<>();
      for (int i = 0; i < 17; i++) {
        records.add(question(i, "Java 基础"));
      }

      service.evaluate(chatClient, "s5", records, null);

      assertThat(batchSizes()).containsExactly(8, 8, 1);
    }
  }

  @Nested
  @DisplayName("结果归并")
  class Merging {

    @Test
    @DisplayName("模型返回条数少于输入条数时缺失项按原索引降级为 0 分")
    void shouldDegradeMissingIndexesToZero() {
      // 只返回索引 1 和 3（0-based），缺失 0 与 2
      stubInvoker(idx -> idx == 1 || idx == 3 ? 90 : null);
      List<interview.guide.common.evaluation.QaRecord> records = List.of(
          question(0, "Java 基础"), question(1, "Java 基础"),
          question(2, "MySQL"), question(3, "MySQL"));

      EvaluationReport report = service.evaluate(chatClient, "s6", records, null);

      assertThat(scoreOf(report, 0)).isZero();
      assertThat(scoreOf(report, 1)).isEqualTo(90);
      assertThat(scoreOf(report, 2)).isZero();
      assertThat(scoreOf(report, 3)).isEqualTo(90);
    }

    @Test
    @DisplayName("模型返回乱序、重复与越界索引时合法项按索引映射")
    void shouldMapByIndexForDisorderedResults() {
      // 模拟模型返回：重复返回索引 0、越界索引 99、乱序返回 2/0
      when(structuredOutputInvoker.invoke(
          any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any(Logger.class)))
      .thenAnswer(invocation -> {
        String userPrompt = invocation.getArgument(2);
        if (!userPrompt.contains("问答记录开始")) {
          return new Object();
        }
        batchPrompts.add(extractQaSection(userPrompt));
        return report(List.of(
            eval(2, 70),
            eval(0, 60),
            eval(99, 100),
            eval(0, 55)));
      });

      List<interview.guide.common.evaluation.QaRecord> records = List.of(
          question(0, "Java 基础"), question(1, "Redis"), question(2, "MySQL"));

      EvaluationReport report = service.evaluate(chatClient, "s7", records, null);

      assertThat(scoreOf(report, 0)).isEqualTo(60);
      assertThat(scoreOf(report, 1)).isZero();
      assertThat(scoreOf(report, 2)).isEqualTo(70);
      assertThat(report.questionDetails()).hasSize(3);
    }

    @Test
    @DisplayName("返回数量一致但索引不可用时按位置兜底")
    void shouldFallBackToPositionalWhenCountsMatch() {
      // 模型返回 3 条但索引全部错误（1-based 重编号）
      when(structuredOutputInvoker.invoke(
          any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any(Logger.class)))
      .thenAnswer(invocation -> {
        String userPrompt = invocation.getArgument(2);
        if (!userPrompt.contains("问答记录开始")) {
          return new Object();
        }
        batchPrompts.add(extractQaSection(userPrompt));
        return report(List.of(
            eval(1, 61),
            eval(2, 62),
            eval(3, 63)));
      });

      List<interview.guide.common.evaluation.QaRecord> records = List.of(
          question(0, "Java 基础"), question(1, "Redis"), question(2, "MySQL"));

      EvaluationReport report = service.evaluate(chatClient, "s8", records, null);

      // 索引整体不可用（1-based 重编号）但数量一致：整批按位置对齐，不重复消费同一份评估
      assertThat(scoreOf(report, 0)).isEqualTo(61);
      assertThat(scoreOf(report, 1)).isEqualTo(62);
      assertThat(scoreOf(report, 2)).isEqualTo(63);
    }
  }

  @Test
  @DisplayName("Prompt 渲染包含内部索引与追问标注")
  void shouldRenderInternalIndexesAndFollowUpMarkers() {
    stubInvoker(idx -> 80);
    service.evaluate(chatClient, "s9",
        List.of(question(5, "Redis"), followUp(6, 5)), null);

    String prompt = batchPrompts.get(0);
    assertThat(prompt).contains("问题 questionIndex=5 [Redis]: 问题5");
    assertThat(prompt).contains("问题 questionIndex=6 [Java 基础]（追问，针对 questionIndex=5）: 追问6");
  }
}
