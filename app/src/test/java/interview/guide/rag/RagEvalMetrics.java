package interview.guide.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RAG 测评指标计算（纯函数，便于单元测试）。
 *
 * <p>约定：逐样本结果为 Map，字段与 RagEvaluationTest 写入一致：
 * split、tags、shouldReject、outcome、hit、firstHitRank、evidenceRecall、
 * predictedReject、evaluateGeneration、rewriteMs、retrievalMs、generationMs、totalMs。
 */
public final class RagEvalMetrics {

  private RagEvalMetrics() {
  }

  /**
   * 汇总一组样本的指标。HARNESS_ERROR 样本被排除并单独计数。
   */
  public static Map<String, Object> metricsOf(List<Map<String, Object>> results) {
    Map<String, Object> m = new LinkedHashMap<>();
    List<Map<String, Object>> valid = new ArrayList<>();
    int harnessErrors = 0;
    for (Map<String, Object> r : results) {
      if ("HARNESS_ERROR".equals(r.get("outcome"))) {
        harnessErrors++;
      } else {
        valid.add(r);
      }
    }
    if (results.isEmpty()) {
      return m;
    }
    m.put("samples", results.size());
    m.put("harnessErrorCount", harnessErrors);
    List<Map<String, Object>> inScope = valid.stream()
        .filter(r -> !Boolean.TRUE.equals(r.get("shouldReject"))).toList();
    if (!inScope.isEmpty()) {
      m.put("inScope", inScope.size());
      m.put("Hit@K(%)", round(100.0 * inScope.stream()
          .filter(r -> Boolean.TRUE.equals(r.get("hit"))).count() / inScope.size()));
      // MRR：未命中样本贡献 0
      m.put("MRR", round(inScope.stream()
          .mapToDouble(r -> {
            Integer rank = (Integer) r.get("firstHitRank");
            return rank == null ? 0.0 : 1.0 / rank;
          }).average().orElse(0)));
      m.put("EvidenceRecall@K", round(inScope.stream()
          .mapToDouble(r -> r.get("evidenceRecall") instanceof Number n ? n.doubleValue() : 0.0)
          .average().orElse(0)));
    }
    putStagePercentiles(m, "retrievalMsP50", "retrievalMsP95", valid, "retrievalMs");
    putStagePercentiles(m, "rerankMsP50", "rerankMsP95", valid, "rerankMs");
    putStagePercentiles(m, "rewriteMsP50", "rewriteMsP95", valid, "rewriteMs");
    List<Map<String, Object>> generation = valid.stream()
        .filter(r -> Boolean.TRUE.equals(r.get("evaluateGeneration"))).toList();
    putStagePercentiles(m, "generationMsP50", "generationMsP95", generation, "generationMs");
    putStagePercentiles(m, "endToEndMsP50", "endToEndMsP95", generation, "totalMs");
    return m;
  }

  /**
   * 拒答混淆矩阵与 Accuracy/Precision/Recall/F1。
   * 矩阵只统计 evaluateGeneration=true 的样本：actual=shouldReject，predicted=predictedReject。
   * 返回键：tp、fn、fp、tn、accuracy、precision、recall、f1。
   */
  public static Map<String, Object> rejectionMetrics(List<Map<String, Object>> results) {
    int tp = 0;
    int fn = 0;
    int fp = 0;
    int tn = 0;
    for (Map<String, Object> r : results) {
      if (!Boolean.TRUE.equals(r.get("evaluateGeneration"))
          || "HARNESS_ERROR".equals(r.get("outcome"))) {
        continue;
      }
      boolean actual = Boolean.TRUE.equals(r.get("shouldReject"));
      boolean predicted = Boolean.TRUE.equals(r.get("predictedReject"));
      if (actual && predicted) {
        tp++;
      } else if (actual) {
        fn++;
      } else if (predicted) {
        fp++;
      } else {
        tn++;
      }
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("tp", tp);
    m.put("fn", fn);
    m.put("fp", fp);
    m.put("tn", tn);
    int total = tp + fn + fp + tn;
    m.put("accuracy", total == 0 ? 0 : round((double) (tp + tn) / total));
    m.put("precision", tp + fp == 0 ? 0 : round((double) tp / (tp + fp)));
    m.put("recall", tp + fn == 0 ? 0 : round((double) tp / (tp + fn)));
    double precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
    double recall = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
    m.put("f1", precision + recall == 0 ? 0 : round(2 * precision * recall / (precision + recall)));
    return m;
  }

  /**
   * 比较同一批样本的 vector-only 与 vector+rerank 两个实验臂。
   * 配对结果只统计有效的站内样本，拒答样本仍保留在状态与一致性统计中。
   */
  public static Map<String, Object> pairedComparison(
      List<Map<String, Object>> vectorResults,
      List<Map<String, Object>> rerankResults,
      List<Map<String, Object>> pairedResults) {
    Map<String, Map<String, Object>> vectorById = indexById(vectorResults, "vectorOnly");
    Map<String, Map<String, Object>> rerankById = indexById(rerankResults, "vectorRerank");
    Map<String, Map<String, Object>> pairedById = indexById(pairedResults, "paired");
    if (!vectorById.keySet().equals(rerankById.keySet())) {
      throw new IllegalArgumentException("两个评测实验臂的样本 ID 集合不一致");
    }
    if (!vectorById.keySet().equals(pairedById.keySet())) {
      throw new IllegalArgumentException("配对结果的样本 ID 集合不一致");
    }

    Map<String, Object> comparison = new LinkedHashMap<>();
    comparison.put("sampleCount", vectorById.size());
    comparison.put("validPairCount", pairedResults.stream()
        .filter(r -> "VALID".equals(r.get("pairStatus"))).count());
    comparison.put("invalidPairCount", pairedResults.stream()
        .filter(r -> "INVALID".equals(r.get("pairStatus"))).count());
    comparison.put("candidateSetMismatchCount", pairedResults.stream()
        .filter(r -> Boolean.FALSE.equals(r.get("candidateSetSame"))).count());
    comparison.put("queryContextMismatchCount", pairedResults.stream()
        .filter(r -> Boolean.FALSE.equals(r.get("queryContextSame"))).count());

    Map<String, Object> vectorMetrics = metricsOf(vectorResults);
    Map<String, Object> rerankMetrics = metricsOf(rerankResults);
    Map<String, Object> delta = new LinkedHashMap<>();
    for (String key : List.of("Hit@K(%)", "MRR", "EvidenceRecall@K")) {
      Number vector = number(vectorMetrics.get(key));
      Number rerank = number(rerankMetrics.get(key));
      if (vector != null && rerank != null) {
        delta.put(key, round(rerank.doubleValue() - vector.doubleValue()));
      }
    }
    comparison.put("metricDelta", delta);

    Map<String, Object> wins = new LinkedHashMap<>();
    wins.put("hit", compareBoolean(vectorById, rerankById));
    wins.put("firstHitRank", compareRank(vectorById, rerankById));
    wins.put("evidenceRecall", compareRecall(vectorById, rerankById));
    comparison.put("wins", wins);

    Map<String, Long> statusCounts = new LinkedHashMap<>();
    rerankResults.forEach(result -> statusCounts.merge(
        String.valueOf(result.get("rerankStatus")), 1L, Long::sum));
    comparison.put("rerankStatusCounts", statusCounts);
    long invalidPairCount = (Long) comparison.get("invalidPairCount");
    long candidateSetMismatchCount = (Long) comparison.get("candidateSetMismatchCount");
    long queryContextMismatchCount = (Long) comparison.get("queryContextMismatchCount");
    comparison.put("comparisonStatus",
        invalidPairCount == 0 && candidateSetMismatchCount == 0 && queryContextMismatchCount == 0
            ? "VALID" : "INVALID");
    return comparison;
  }

  private static Map<String, Map<String, Object>> indexById(
      List<Map<String, Object>> results, String arm) {
    Map<String, Map<String, Object>> indexed = new HashMap<>();
    for (Map<String, Object> result : results) {
      Object id = result.get("id");
      if (!(id instanceof String sampleId) || sampleId.isBlank()
          || indexed.put(sampleId, result) != null) {
        throw new IllegalArgumentException(arm + " 实验臂存在空或重复样本 ID");
      }
    }
    return indexed;
  }

  private static Map<String, Object> compareBoolean(
      Map<String, Map<String, Object>> vectorById,
      Map<String, Map<String, Object>> rerankById) {
    long wins = 0;
    long losses = 0;
    long ties = 0;
    for (String id : vectorById.keySet()) {
      if (!comparable(vectorById.get(id), rerankById.get(id))) {
        continue;
      }
      boolean vector = Boolean.TRUE.equals(vectorById.get(id).get("hit"));
      boolean rerank = Boolean.TRUE.equals(rerankById.get(id).get("hit"));
      if (vector == rerank) {
        ties++;
      } else if (rerank) {
        wins++;
      } else {
        losses++;
      }
    }
    return counts(wins, losses, ties);
  }

  private static Map<String, Object> compareRank(
      Map<String, Map<String, Object>> vectorById,
      Map<String, Map<String, Object>> rerankById) {
    long wins = 0;
    long losses = 0;
    long ties = 0;
    for (String id : vectorById.keySet()) {
      if (!comparable(vectorById.get(id), rerankById.get(id))) {
        continue;
      }
      Integer vector = integer(vectorById.get(id).get("firstHitRank"));
      Integer rerank = integer(rerankById.get(id).get("firstHitRank"));
      int vectorRank = vector == null ? Integer.MAX_VALUE : vector;
      int rerankRank = rerank == null ? Integer.MAX_VALUE : rerank;
      if (vectorRank == rerankRank) {
        ties++;
      } else if (rerankRank < vectorRank) {
        wins++;
      } else {
        losses++;
      }
    }
    return counts(wins, losses, ties);
  }

  private static Map<String, Object> compareRecall(
      Map<String, Map<String, Object>> vectorById,
      Map<String, Map<String, Object>> rerankById) {
    long wins = 0;
    long losses = 0;
    long ties = 0;
    for (String id : vectorById.keySet()) {
      if (!comparable(vectorById.get(id), rerankById.get(id))) {
        continue;
      }
      double vector = number(vectorById.get(id).get("evidenceRecall"), 0.0);
      double rerank = number(rerankById.get(id).get("evidenceRecall"), 0.0);
      if (Double.compare(vector, rerank) == 0) {
        ties++;
      } else if (rerank > vector) {
        wins++;
      } else {
        losses++;
      }
    }
    return counts(wins, losses, ties);
  }

  private static boolean comparable(Map<String, Object> vector, Map<String, Object> rerank) {
    return !Boolean.TRUE.equals(vector.get("shouldReject"))
        && !"HARNESS_ERROR".equals(vector.get("outcome"))
        && !"HARNESS_ERROR".equals(rerank.get("outcome"));
  }

  private static Map<String, Object> counts(long wins, long losses, long ties) {
    Map<String, Object> counts = new LinkedHashMap<>();
    counts.put("wins", wins);
    counts.put("losses", losses);
    counts.put("ties", ties);
    return counts;
  }

  private static Number number(Object value) {
    return value instanceof Number number ? number : null;
  }

  private static double number(Object value, double fallback) {
    Number number = number(value);
    return number == null ? fallback : number.doubleValue();
  }

  private static Integer integer(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private static void putStagePercentiles(Map<String, Object> m, String p50Key, String p95Key,
                                          List<Map<String, Object>> results, String field) {
    List<Long> values = results.stream()
        .map(r -> r.get(field))
        .filter(Objects::nonNull)
        .map(v -> ((Number) v).longValue())
        .sorted().toList();
    if (!values.isEmpty()) {
      m.put(p50Key, percentile(values, 0.50));
      m.put(p95Key, percentile(values, 0.95));
    }
  }

  private static long percentile(List<Long> sorted, double p) {
    int index = (int) Math.min(sorted.size() - 1L, Math.round(p * (sorted.size() - 1)));
    return sorted.get(index);
  }

  private static double round(double value) {
    return Math.round(value * 10000) / 10000.0;
  }
}
