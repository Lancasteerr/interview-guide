package interview.guide.rag;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.knowledgebase.service.RagQueryExecution;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.fail;

/**
 * RAG 测评（P1-01）。
 *
 * <p>运行方式见 app/src/test/resources/rag-eval/README.md：
 * <pre>RUN_RAG_EVAL=true REDIS_DATABASE=1 ./gradlew :app:ragEvaluation --no-daemon</pre>
 *
 * <p>双保险：rag-eval 标签（普通 :app:test 排除）+ RUN_RAG_EVAL 环境变量。
 * 环境使用独立可销毁的 PostgreSQL 逻辑库 interview_guide_rag_eval，不触碰开发库。
 */
@Tag("rag-eval")
@EnabledIfEnvironmentVariable(named = "RUN_RAG_EVAL", matches = "true")
@ActiveProfiles("rag-eval")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RagEvaluationTest {

  private static final String EVAL_DB = "interview_guide_rag_eval";
  private static final String DATASET = "rag-eval/dataset-v1.jsonl";
  private static final String REPORT_DIR =
      System.getProperty("ragEval.reportDir", "build/reports/rag-eval");
  private static final String RUN_ID =
      "rag-eval-" + System.getProperty("ragEval.runTag", "baseline") + "-" + System.currentTimeMillis();

  private static String evalDbUrl;
  private static String sourceDbUrl;
  private static boolean dbCreated;

  @Autowired
  private KnowledgeBaseRepository knowledgeBaseRepository;
  @Autowired
  private KnowledgeBaseVectorService vectorService;
  @Autowired
  private KnowledgeBaseQueryService queryService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Map<String, Long> fixtureKbIds = new HashMap<>();

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    if (evalDbUrl != null) {
      registry.add("spring.datasource.url", () -> evalDbUrl);
    }
  }

  @BeforeAll
  static void recreateDatabase() {
    String host = env("POSTGRES_HOST", "localhost");
    String port = env("POSTGRES_PORT", "5432");
    String user = env("POSTGRES_USER", "postgres");
    String password = env("POSTGRES_PASSWORD", "123456");
    evalDbUrl = "jdbc:postgresql://" + host + ":" + port + "/" + EVAL_DB;
    sourceDbUrl = "jdbc:postgresql://" + host + ":" + port + "/" + env("POSTGRES_DB", "interview_guide");
    try (Connection conn = DriverManager.getConnection(
        "jdbc:postgresql://" + host + ":" + port + "/postgres", user, password);
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP DATABASE IF EXISTS " + EVAL_DB);
      stmt.execute("CREATE DATABASE " + EVAL_DB);
      dbCreated = true;
    } catch (Exception e) {
      fail("RAG 测评前置失败：无法重建独立测评数据库 " + EVAL_DB + "：" + e.getMessage());
    }
  }

  @AfterAll
  static void dropDatabase() {
    if (!dbCreated) {
      return;
    }
    try (Connection conn = DriverManager.getConnection(
        "jdbc:postgresql://" + env("POSTGRES_HOST", "localhost") + ":" + env("POSTGRES_PORT", "5432") + "/postgres",
        env("POSTGRES_USER", "postgres"), env("POSTGRES_PASSWORD", "123456"));
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP DATABASE IF EXISTS " + EVAL_DB + " WITH (FORCE)");
    } catch (Exception ignored) {
      // 清理失败不影响已生成的报告；下次运行会先 DROP 再 CREATE
    }
  }

  @Test
  void runEvaluation() throws Exception {
    List<RagEvalSample> samples = loadSamples();
    seedProvidersFromSourceDatabase();
    Map<String, String> fixtureContents = loadFixtures(samples);
    vectorizeFixtures(fixtureContents);

    List<Map<String, Object>> sampleResults = new ArrayList<>();
    List<Map<String, Object>> badCases = new ArrayList<>();
    List<Map<String, Object>> faithfulnessReview = new ArrayList<>();
    int[][] confusion = new int[2][2]; // [actualReject][predictedReject]

    for (RagEvalSample sample : samples) {
      Map<String, Object> result = evaluateSample(sample, faithfulnessReview, badCases, confusion);
      sampleResults.add(result);
    }

    Map<String, Object> report = buildReport(samples, sampleResults, badCases, faithfulnessReview, confusion);
    RagEvalReportWriter.write(Path.of(REPORT_DIR), RUN_ID, report);

    long harnessErrors = sampleResults.stream().filter(r -> "HARNESS_ERROR".equals(r.get("outcome"))).count();
    if (harnessErrors > 0) {
      fail(harnessErrors + " 个样本发生环境级错误，报告见 " + REPORT_DIR + "/" + RUN_ID + ".md");
    }
  }

  // ========== 样本执行 ==========

  private Map<String, Object> evaluateSample(RagEvalSample sample,
                                             List<Map<String, Object>> faithfulnessReview,
                                             List<Map<String, Object>> badCases,
                                             int[][] confusion) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", sample.id());
    result.put("split", sample.split());
    result.put("tags", sample.tags());
    result.put("question", sample.question());
    result.put("shouldReject", sample.shouldReject());
    long totalStart = System.nanoTime();
    try {
      Long kbId = fixtureKbIds.get(sample.fixture());
      if (kbId == null) {
        throw new IllegalStateException("fixture 未向量化: " + sample.fixture());
      }
      List<Message> history = toMessages(sample.history());

      RagQueryExecution execution;
      if (sample.evaluateGeneration()) {
        List<String> chunks = new ArrayList<>();
        List<RagQueryExecution> trace = new ArrayList<>();
        queryService
            .answerQuestionStream(List.of(kbId), sample.question(), history, trace::add)
            .doOnNext(chunks::add)
            .blockLast();
        execution = trace.isEmpty()
            ? new RagQueryExecution(sample.question(), sample.question(), List.of(), 0, 0,
                List.of(), 0, 0, 0, String.join("", chunks), "NO_RESULT")
            : trace.getFirst();
      } else {
        execution = queryService.retrieveOnly(List.of(kbId), sample.question(), history);
      }

      List<String> hitEvidenceIds = new ArrayList<>();
      Integer firstHitRank = null;
      List<String> docTexts = execution.retrievedDocs().stream()
          .map(RagQueryExecution.RetrievedDoc::text).toList();
      for (RagEvalSample.Evidence evidence : sample.expectedEvidence()) {
        String normalizedEvidence = RagEvalSample.normalize(evidence.text());
        for (int i = 0; i < docTexts.size(); i++) {
          if (docTexts.get(i) != null
              && RagEvalSample.normalize(docTexts.get(i)).contains(normalizedEvidence)) {
            hitEvidenceIds.add(evidence.id());
            if (firstHitRank == null) {
              firstHitRank = i + 1;
            }
            break;
          }
        }
      }
      boolean hit = !hitEvidenceIds.isEmpty() && !sample.expectedEvidence().isEmpty();
      double evidenceRecall = sample.expectedEvidence().isEmpty() ? 1.0
          : (double) hitEvidenceIds.size() / sample.expectedEvidence().size();
      boolean predictedReject = "NO_RESULT".equals(execution.outcome());

      if (sample.shouldReject()) {
        confusion[sample.shouldReject() ? 1 : 0][predictedReject ? 1 : 0]++;
      }
      if (sample.evaluateGeneration() && !sample.shouldReject()) {
        faithfulnessReview.add(faithfulnessEntry(sample, execution));
      }

      result.put("outcome", execution.outcome());
      result.put("rewrittenQuestion", execution.rewrittenQuestion());
      result.put("attemptedQueries", execution.attemptedQueries());
      result.put("resolvedTopK", execution.resolvedTopK());
      result.put("resolvedMinScore", execution.resolvedMinScore());
      result.put("retrievedDocCount", execution.retrievedDocs().size());
      result.put("hit", hit);
      result.put("hitEvidenceIds", hitEvidenceIds);
      result.put("firstHitRank", firstHitRank);
      result.put("evidenceRecall", evidenceRecall);
      result.put("predictedReject", predictedReject);
      result.put("rewriteMs", execution.rewriteDurationMs());
      result.put("retrievalMs", execution.retrievalDurationMs());
      result.put("generationMs", execution.generationDurationMs());
      result.put("totalMs", (System.nanoTime() - totalStart) / 1_000_000);

      collectBadCase(sample, result, firstHitRank, evidenceRecall, predictedReject, badCases);
      return result;
    } catch (Exception e) {
      result.put("outcome", "HARNESS_ERROR");
      result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
      result.put("totalMs", (System.nanoTime() - totalStart) / 1_000_000);
      badCases.add(badCase(sample, "HARNESS_ERROR",
          e.getClass().getSimpleName() + ": " + e.getMessage()));
      return result;
    }
  }

  private void collectBadCase(RagEvalSample sample, Map<String, Object> result, Integer firstHitRank,
                              double evidenceRecall, boolean predictedReject, List<Map<String, Object>> badCases) {
    if (sample.shouldReject() && !predictedReject) {
      badCases.add(badCase(sample, "OOS_NOT_REJECTED", "知识库外问题未被拒答"));
    } else if (!sample.shouldReject() && predictedReject) {
      badCases.add(badCase(sample, "FALSE_REJECT", "站内问题被误拒答"));
    } else if (!sample.shouldReject() && Boolean.FALSE.equals(result.get("hit"))) {
      badCases.add(badCase(sample, "NO_EVIDENCE_HIT", "Top-K 内未命中任何证据锚点"));
    } else if (!sample.shouldReject() && evidenceRecall < 1.0) {
      badCases.add(badCase(sample, "EVIDENCE_INCOMPLETE", "证据锚点未全部召回，Recall=" + evidenceRecall));
    } else if (!sample.shouldReject() && firstHitRank != null && firstHitRank > 3) {
      badCases.add(badCase(sample, "LOW_FIRST_RANK", "首个命中排名=" + firstHitRank));
    }
  }

  private Map<String, Object> badCase(RagEvalSample sample, String reason, String detail) {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("id", sample.id());
    c.put("question", sample.question());
    c.put("tags", sample.tags());
    c.put("reason", reason);
    c.put("detail", detail);
    return c;
  }

  private Map<String, Object> faithfulnessEntry(RagEvalSample sample, RagQueryExecution execution) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("id", sample.id());
    f.put("question", sample.question());
    f.put("expectedEvidence", sample.expectedEvidence().stream().map(RagEvalSample.Evidence::text).toList());
    f.put("answer", execution.answer());
    f.put("evidenceIds", sample.expectedEvidence().stream().map(RagEvalSample.Evidence::id).toList());
    f.put("faithfulnessStatus", "PENDING");
    f.put("unsupportedClaims", null);
    f.put("reason", null);
    f.put("reviewer", null);
    f.put("reviewedAt", null);
    return f;
  }

  // ========== 报告汇总 ==========

  private Map<String, Object> buildReport(List<RagEvalSample> samples,
                                          List<Map<String, Object>> sampleResults,
                                          List<Map<String, Object>> badCases,
                                          List<Map<String, Object>> faithfulnessReview,
                                          int[][] confusion) throws Exception {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("runId", RUN_ID);
    report.put("timestamp", java.time.Instant.now().toString());
    report.put("environment", buildEnvironment());
    report.put("metrics", buildMetrics(sampleResults));
    report.put("rejection", buildRejection(confusion));
    report.put("badCases", badCases);
    report.put("faithfulnessReview", faithfulnessReview);
    report.put("samples", sampleResults);
    report.put("datasetVersion", DATASET);
    report.put("datasetSize", samples.size());
    return report;
  }

  private Map<String, Object> buildEnvironment() throws Exception {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("gitSha", gitSha());
    env.put("datasetSha256", sha256(new ClassPathResource(DATASET).getInputStream().readAllBytes()));
    for (String fixture : List.of("java-guide.md", "project-guide.md")) {
      env.put("fixtureSha256:" + fixture,
          sha256(new ClassPathResource("rag-eval/fixtures/" + fixture).getInputStream().readAllBytes()));
    }
    for (String prompt : List.of("knowledgebase-query-system.st", "knowledgebase-query-user.st",
        "knowledgebase-query-rewrite.st")) {
      env.put("promptSha256:" + prompt,
          sha256(new ClassPathResource("prompts/" + prompt).getInputStream().readAllBytes()));
    }
    env.put("rewriteEnabled", System.getenv("APP_AI_RAG_REWRITE_ENABLED") == null
        ? "false(rag-eval Profile 默认)" : System.getenv("APP_AI_RAG_REWRITE_ENABLED"));
    env.put("redisDatabase", System.getenv().getOrDefault("REDIS_DATABASE", "0"));
    env.put("evalDatabase", EVAL_DB + "（每 run 重建）");
    env.put("tokenUsage", "null（当前链路 .content() 无法取得 usage，补齐属 P1-04）");
    return env;
  }

  private Map<String, Object> buildMetrics(List<Map<String, Object>> results) {
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("overall", metricsOf(results));
    metrics.put("dev", metricsOf(results.stream()
        .filter(r -> "dev".equals(r.get("split"))).toList()));
    metrics.put("holdout", metricsOf(results.stream()
        .filter(r -> "holdout".equals(r.get("split"))).toList()));
    results.stream().map(r -> (List<String>) r.get("tags"))
        .flatMap(List::stream).distinct().sorted()
        .forEach(tag -> metrics.put("tag:" + tag, metricsOf(results.stream()
            .filter(r -> ((List<String>) r.get("tags")).contains(tag)).toList())));
    return metrics;
  }

  private Map<String, Object> metricsOf(List<Map<String, Object>> results) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (results.isEmpty()) {
      return m;
    }
    List<Map<String, Object>> inScope = results.stream()
        .filter(r -> !Boolean.TRUE.equals(r.get("shouldReject"))).toList();
    double hitRate = inScope.isEmpty() ? 0 : inScope.stream()
        .filter(r -> Boolean.TRUE.equals(r.get("hit"))).count() * 100.0 / inScope.size();
    double mrr = inScope.isEmpty() ? 0 : inScope.stream()
        .map(r -> (Integer) r.get("firstHitRank"))
        .filter(java.util.Objects::nonNull)
        .mapToInt(Integer::intValue)
        .mapToDouble(rank -> 1.0 / rank).average().orElse(0);
    double avgRecall = inScope.isEmpty() ? 0 : inScope.stream()
        .mapToDouble(r -> (Double) r.get("evidenceRecall")).average().orElse(0);
    List<Long> totals = results.stream()
        .filter(r -> r.get("totalMs") instanceof Long)
        .map(r -> (Long) r.get("totalMs")).sorted().toList();
    m.put("samples", results.size());
    m.put("inScope", inScope.size());
    m.put("Hit@K(%)", round(hitRate));
    m.put("MRR", round(mrr));
    m.put("EvidenceRecall@K", round(avgRecall));
    if (!totals.isEmpty()) {
      m.put("totalMsP50", percentile(totals, 0.50));
      m.put("totalMsP95", percentile(totals, 0.95));
    }
    return m;
  }

  private Map<String, Object> buildRejection(int[][] confusion) {
    Map<String, Object> r = new LinkedHashMap<>();
    int tp = confusion[1][1];
    int fn = confusion[1][0];
    int fp = confusion[0][1];
    int tn = confusion[0][0];
    r.put("confusion(actual x predicted)", "reject-correct=" + tp + ", reject-missed=" + fn
        + ", false-reject=" + fp + ", reject-correct-true=" + tn);
    double precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
    double recall = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
    double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
    r.put("precision", round(precision));
    r.put("recall", round(recall));
    r.put("f1", round(f1));
    return r;
  }

  // ========== 数据准备 ==========

  private List<RagEvalSample> loadSamples() throws IOException {
    List<String> lines = new String(
            new ClassPathResource(DATASET).getInputStream().readAllBytes(), StandardCharsets.UTF_8)
        .lines().filter(line -> !line.isBlank()).toList();
    List<RagEvalSample> samples = new ArrayList<>();
    for (String line : lines) {
      Map<String, Object> raw = objectMapper.readValue(line, Map.class);
      samples.add(RagEvalSample.fromMap(raw));
    }
    return samples;
  }

  private Map<String, String> loadFixtures(List<RagEvalSample> samples) throws IOException {
    Map<String, String> contents = new HashMap<>();
    for (RagEvalSample sample : samples) {
      if (!contents.containsKey(sample.fixture())) {
        contents.put(sample.fixture(), new String(
            new ClassPathResource("rag-eval/fixtures/" + sample.fixture())
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return contents;
  }

  private void vectorizeFixtures(Map<String, String> fixtureContents) {
    fixtureContents.forEach((fixture, content) -> {
      KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
      kb.setName("rag-eval-" + fixture);
      kb.setOriginalFilename(fixture);
      kb.setFileHash("rag-eval-" + RUN_ID + "-" + fixture);
      kb.setContentType("text/markdown");
      kb.setFileSize((long) content.length());
      kb.setVectorStatus(VectorStatus.PENDING);
      KnowledgeBaseEntity saved = knowledgeBaseRepository.save(kb);
      vectorService.vectorizeAndStore(saved.getId(), content);
      fixtureKbIds.put(fixture, saved.getId());
    });
  }

  /**
   * 测评库启动时会按 application.yml seed 空 key 的 Provider，
   * 这里用开发库的真实配置整体替换（密文使用相同加密钥，可直接复制），
   * 使 Embedding / Chat 走与开发环境一致的 Provider。
   */
  private void seedProvidersFromSourceDatabase() throws Exception {
    try (Connection source = DriverManager.getConnection(sourceDbUrl,
        env("POSTGRES_USER", "postgres"), env("POSTGRES_PASSWORD", "123456"));
        Connection target = DriverManager.getConnection(evalDbUrl,
            env("POSTGRES_USER", "postgres"), env("POSTGRES_PASSWORD", "123456"))) {
      replaceTable(source, target, "llm_provider_config");
      replaceTable(source, target, "llm_global_setting");
    }
  }

  private void replaceTable(Connection source, Connection target, String table) throws Exception {
    try (Statement tgt = target.createStatement()) {
      tgt.execute("DELETE FROM " + table);
    }
    copyTable(source, target, table);
  }

  private void copyTable(Connection source, Connection target, String table) throws Exception {
    try (Statement src = source.createStatement();
        var rs = src.executeQuery("SELECT * FROM " + table);
        Statement tgt = target.createStatement()) {
      var meta = rs.getMetaData();
      int columnCount = meta.getColumnCount();
      List<String> columns = new ArrayList<>();
      for (int i = 1; i <= columnCount; i++) {
        columns.add(meta.getColumnName(i));
      }
      String columnList = String.join(", ", columns);
      while (rs.next()) {
        List<String> values = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
          Object value = rs.getObject(i);
          values.add(value == null ? "NULL" : "'" + value.toString().replace("'", "''") + "'");
        }
        tgt.executeUpdate("INSERT INTO " + table + " (" + columnList + ") VALUES ("
            + String.join(", ", values) + ")");
      }
    }
  }

  private List<Message> toMessages(List<RagEvalSample.HistoryMessage> history) {
    List<Message> messages = new ArrayList<>();
    for (RagEvalSample.HistoryMessage m : history) {
      messages.add("assistant".equals(m.role())
          ? new AssistantMessage(m.content()) : new UserMessage(m.content()));
    }
    return messages;
  }

  // ========== 工具 ==========

  private static long percentile(List<Long> sorted, double p) {
    int index = (int) Math.min(sorted.size() - 1, Math.round(p * (sorted.size() - 1)));
    return sorted.get(index);
  }

  private static double round(double value) {
    return Math.round(value * 10000) / 10000.0;
  }

  private static String gitSha() {
    try {
      Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
          .redirectErrorStream(true).start();
      p.waitFor();
      return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (Exception e) {
      return "unknown";
    }
  }

  private static String sha256(byte[] data) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(data)).substring(0, 16);
  }

  private static String env(String key, String defaultValue) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? defaultValue : value;
  }
}
