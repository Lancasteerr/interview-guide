package interview.guide.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 测评报告兼容契约")
class RagEvalReportWriterTest {

  @TempDir
  Path reportDir;

  @Test
  @DisplayName("单臂报告保留 rerank 状态和耗时字段")
  void singleArmReportKeepsRerankFields() throws Exception {
    Map<String, Object> sample = new LinkedHashMap<>();
    sample.put("id", "fact-01");
    sample.put("split", "dev");
    sample.put("tags", List.of("直接事实题"));
    sample.put("outcome", "RETRIEVED");
    sample.put("rerankStatus", "DISABLED");
    sample.put("hit", true);
    sample.put("firstHitRank", 1);
    sample.put("evidenceRecall", 1.0);
    sample.put("totalMs", 12L);

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("runId", "contract-run");
    report.put("environment", Map.of("gitSha", "test-sha"));
    report.put("metrics", Map.of("overall", Map.of("rerankMsP50", 0L)));
    report.put("rejection", Map.of());
    report.put("badCases", List.of());
    report.put("faithfulnessReview", List.of());
    report.put("samples", List.of(sample));

    RagEvalReportWriter.write(reportDir, "contract-run", report);

    String markdown = Files.readString(
        reportDir.resolve("contract-run.md"), StandardCharsets.UTF_8);
    assertThat(markdown)
        .contains("| rerank |")
        .contains("| DISABLED |")
        .contains("rerankMsP50");
  }

  @Test
  @DisplayName("配对报告输出两个实验臂和逐样本对照表")
  void pairedReportContainsBothArms() throws Exception {
    Map<String, Object> vectorSample = new LinkedHashMap<>();
    vectorSample.put("id", "fact-01");
    vectorSample.put("split", "dev");
    vectorSample.put("hit", false);
    vectorSample.put("firstHitRank", null);
    vectorSample.put("evidenceRecall", 0.0);
    vectorSample.put("rerankStatus", "DISABLED");

    Map<String, Object> rerankSample = new LinkedHashMap<>();
    rerankSample.put("id", "fact-01");
    rerankSample.put("split", "dev");
    rerankSample.put("hit", true);
    rerankSample.put("firstHitRank", 1);
    rerankSample.put("evidenceRecall", 1.0);
    rerankSample.put("rerankStatus", "SUCCESS");

    Map<String, Object> pair = new LinkedHashMap<>();
    pair.put("id", "fact-01");
    pair.put("split", "dev");
    pair.put("pairStatus", "VALID");
    pair.put("vectorOnly", vectorSample);
    pair.put("vectorRerank", rerankSample);

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("runId", "paired-run");
    report.put("evaluationMode", "paired-rerank");
    report.put("generationEvaluation", false);
    report.put("rejectionEvaluation", "NOT_EVALUATED");
    report.put("status", "VALID");
    report.put("environment", Map.of("gitSha", "test-sha"));
    report.put("arms", Map.of(
        "vectorOnly", Map.of("metrics", Map.of("overall", Map.of("MRR", 0.0))),
        "vectorRerank", Map.of("metrics", Map.of("overall", Map.of("MRR", 1.0)))));
    report.put("comparison", Map.of("metricDelta", Map.of("MRR", 1.0)));
    report.put("badCases", List.of());
    report.put("samples", List.of(pair));

    RagEvalReportWriter.write(reportDir, "paired-run", report);

    String markdown = Files.readString(
        reportDir.resolve("paired-run.md"), StandardCharsets.UTF_8);
    assertThat(markdown)
        .contains("vectorOnly")
        .contains("vectorRerank")
        .contains("metricDelta")
        .contains("generationEvaluation: false")
        .contains("rejectionEvaluation: NOT_EVALUATED")
        .contains("fact-01")
        .contains("SUCCESS");
  }
}
