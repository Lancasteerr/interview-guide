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
}
