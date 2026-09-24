package interview.guide.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RAG 测评指标计算单元测试（不调用模型）。
 */
@DisplayName("RAG 测评指标计算")
class RagEvalMetricsTest {

  private Map<String, Object> sample(String outcome, boolean shouldReject, boolean evaluateGeneration,
                                     Boolean hit, Integer firstHitRank, double evidenceRecall,
                                     boolean predictedReject) {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("outcome", outcome);
    m.put("shouldReject", shouldReject);
    m.put("evaluateGeneration", evaluateGeneration);
    m.put("hit", hit);
    m.put("firstHitRank", firstHitRank);
    m.put("evidenceRecall", evidenceRecall);
    m.put("predictedReject", predictedReject);
    m.put("retrievalMs", 100L);
    m.put("rerankMs", 20L);
    m.put("rewriteMs", 50L);
    m.put("generationMs", 5000L);
    m.put("totalMs", 5200L);
    return m;
  }

  @Test
  @DisplayName("MRR 中未命中样本贡献 0，而不是被剔除")
  void mrrCountsMissAsZero() {
    // 3 个站内样本：排名 1、未命中、排名 5 → MRR = (1 + 0 + 0.2) / 3
    List<Map<String, Object>> results = List.of(
        sample("RETRIEVED", false, false, true, 1, 1.0, false),
        sample("RETRIEVED", false, false, false, null, 0.0, false),
        sample("RETRIEVED", false, false, true, 5, 1.0, false));

    Map<String, Object> metrics = RagEvalMetrics.metricsOf(results);

    assertThat(metrics.get("MRR")).isEqualTo(0.4);
    assertThat(metrics.get("Hit@K(%)")).isEqualTo(66.6667);
  }

  @Test
  @DisplayName("拒答混淆矩阵统计全部生成样本，含误拒答")
  void rejectionMatrixCoversAllGenerationSamples() {
    List<Map<String, Object>> results = List.of(
        sample("NO_RESULT", true, true, false, null, 1.0, true),     // tp
        sample("ANSWERED", true, true, false, null, 1.0, false),    // fn
        sample("NO_RESULT", false, true, true, 1, 1.0, true),       // fp（站内误拒答）
        sample("ANSWERED", false, true, true, 1, 1.0, false),       // tn
        sample("RETRIEVED", false, false, true, 1, 1.0, false));    // 非生成样本不计入

    Map<String, Object> rejection = RagEvalMetrics.rejectionMetrics(results);

    assertThat(rejection.get("tp")).isEqualTo(1);
    assertThat(rejection.get("fn")).isEqualTo(1);
    assertThat(rejection.get("fp")).isEqualTo(1);
    assertThat(rejection.get("tn")).isEqualTo(1);
    assertThat((Double) rejection.get("accuracy")).isEqualTo(0.5);
    assertThat((Double) rejection.get("f1")).isEqualTo(0.5);
  }

  @Test
  @DisplayName("HARNESS_ERROR 样本被排除并单独计数")
  void harnessErrorsExcluded() {
    List<Map<String, Object>> results = List.of(
        sample("RETRIEVED", false, false, true, 1, 1.0, false),
        sample("HARNESS_ERROR", false, true, null, null, 0.0, false));

    Map<String, Object> metrics = RagEvalMetrics.metricsOf(results);

    assertThat(metrics.get("harnessErrorCount")).isEqualTo(1);
    assertThat(metrics.get("samples")).isEqualTo(2);
    assertThat(metrics.get("MRR")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("分阶段耗时按生成子集与检索子集分别输出")
  void stagePercentilesSplit() {
    List<Map<String, Object>> results = List.of(
        sample("RETRIEVED", false, false, true, 1, 1.0, false),
        sample("ANSWERED", false, true, true, 1, 1.0, false));

    Map<String, Object> metrics = RagEvalMetrics.metricsOf(results);

    // 检索耗时在两个样本上都输出；生成与端到端只在生成子集上输出
    assertThat(metrics).containsKeys(
        "retrievalMsP50", "retrievalMsP95", "rerankMsP50", "rerankMsP95", "rewriteMsP50");
    assertThat(metrics).containsKeys("generationMsP50", "endToEndMsP50");
  }

  @Test
  @DisplayName("配对指标输出 delta 以及命中、排名、覆盖率胜负平")
  void pairedComparisonComputesDeltaAndWinners() {
    Map<String, Object> vectorA = result("a", sample(
        "RETRIEVED", false, false, true, 5, 0.5, false), "DISABLED");
    Map<String, Object> vectorB = result("b", sample(
        "NO_RESULT", false, false, false, null, 0.0, false), "DISABLED");
    Map<String, Object> vectorOos = result("oos", sample(
        "NO_RESULT", true, false, false, null, 1.0, true), "DISABLED");
    Map<String, Object> rerankA = result("a", sample(
        "RETRIEVED", false, false, true, 1, 1.0, false), "SUCCESS");
    Map<String, Object> rerankB = result("b", sample(
        "RETRIEVED", false, false, true, 3, 0.5, false), "SUCCESS");
    Map<String, Object> rerankOos = result("oos", sample(
        "NO_RESULT", true, false, false, null, 1.0, true), "SKIPPED");

    List<Map<String, Object>> paired = List.of(
        pair("a"), pair("b"), pair("oos"));
    Map<String, Object> comparison = RagEvalMetrics.pairedComparison(
        List.of(vectorA, vectorB, vectorOos),
        List.of(rerankA, rerankB, rerankOos),
        paired);

    Map<String, Object> delta = (Map<String, Object>) comparison.get("metricDelta");
    assertThat(delta).containsEntry("Hit@K(%)", 50.0)
        .containsEntry("MRR", 0.5667)
        .containsEntry("EvidenceRecall@K", 0.5);
    Map<String, Object> wins = (Map<String, Object>) comparison.get("wins");
    assertThat((Map<String, Object>) wins.get("hit"))
        .containsEntry("wins", 1L).containsEntry("losses", 0L).containsEntry("ties", 1L);
    assertThat((Map<String, Object>) wins.get("firstHitRank"))
        .containsEntry("wins", 2L).containsEntry("losses", 0L).containsEntry("ties", 0L);
    assertThat(comparison.get("candidateSetMismatchCount")).isEqualTo(0L);
    assertThat((Map<String, Long>) comparison.get("rerankStatusCounts"))
        .containsEntry("SUCCESS", 2L).containsEntry("SKIPPED", 1L);
    assertThat(comparison.get("comparisonStatus")).isEqualTo("VALID");
  }

  @Test
  @DisplayName("两个实验臂样本 ID 不一致时拒绝生成对照结论")
  void pairedComparisonRejectsMismatchedIds() {
    Map<String, Object> vector = result("a", sample(
        "RETRIEVED", false, false, true, 1, 1.0, false), "DISABLED");
    Map<String, Object> rerank = result("b", sample(
        "RETRIEVED", false, false, true, 1, 1.0, false), "SUCCESS");

    assertThatThrownBy(() -> RagEvalMetrics.pairedComparison(
        List.of(vector), List.of(rerank), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("样本 ID 集合不一致");
  }

  @Test
  @DisplayName("HARNESS_ERROR 不参与配对胜负统计")
  void harnessErrorsAreExcludedFromPairedWins() {
    Map<String, Object> vector = result("a", sample(
        "HARNESS_ERROR", false, false, null, null, 0.0, false), "DISABLED");
    Map<String, Object> rerank = result("a", sample(
        "RETRIEVED", false, false, true, 1, 1.0, false), "SUCCESS");

    Map<String, Object> comparison = RagEvalMetrics.pairedComparison(
        List.of(vector), List.of(rerank), List.of(pair("a")));

    assertThat((Map<String, Object>) ((Map<String, Object>) comparison.get("wins")).get("hit"))
        .containsEntry("wins", 0L)
        .containsEntry("losses", 0L)
        .containsEntry("ties", 0L);
  }

  private Map<String, Object> result(String id, Map<String, Object> sample, String status) {
    sample.put("id", id);
    sample.put("rerankStatus", status);
    sample.put("rerankReason", "SUCCESS".equals(status) ? "none" : "disabled");
    return sample;
  }

  private Map<String, Object> pair(String id) {
    Map<String, Object> pair = new java.util.HashMap<>();
    pair.put("id", id);
    pair.put("pairStatus", "VALID");
    pair.put("candidateSetSame", true);
    pair.put("queryContextSame", true);
    return pair;
  }
}
