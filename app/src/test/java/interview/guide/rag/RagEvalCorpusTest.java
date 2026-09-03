package interview.guide.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 测评语料契约测试（不调用任何模型，随普通测试运行）。
 *
 * <p>固定校验数据集结构、锚点有效性、语料规模与跨 Chunk 约束，
 * 防止数据集或 fixture 演化后悄悄破坏测评的有效性。
 */
@DisplayName("RAG 测评语料契约")
class RagEvalCorpusTest {

  private static final int MAX_TOP_K = 20;

  private final TextSplitter splitter = TokenTextSplitter.builder().build();

  private List<RagEvalSample> samples() throws IOException {
    String jsonl = new String(
        new ClassPathResource("rag-eval/dataset-v1.jsonl").getInputStream().readAllBytes(),
        StandardCharsets.UTF_8);
    return jsonl.lines().filter(l -> !l.isBlank())
        .map(l -> {
          try {
            return RagEvalSample.fromMap(
                new tools.jackson.databind.ObjectMapper().readValue(l, Map.class));
          } catch (Exception e) {
            throw new IllegalStateException("样本解析失败: " + l, e);
          }
        }).toList();
  }

  private Map<String, String> fixtures(List<RagEvalSample> samples) throws IOException {
    Map<String, String> fixtures = new HashMap<>();
    for (RagEvalSample s : samples) {
      if (!fixtures.containsKey(s.fixture())) {
        fixtures.put(s.fixture(), new String(
            new ClassPathResource("rag-eval/fixtures/" + s.fixture())
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return fixtures;
  }

  @Nested
  @DisplayName("数据集结构")
  class DatasetContract {

    @Test
    @DisplayName("总数 40 条，dev/holdout 为 30/10")
    void totalCountAndSplit() throws IOException {
      List<RagEvalSample> all = samples();
      assertThat(all).hasSize(40);
      assertThat(all.stream().filter(s -> "dev".equals(s.split()))).hasSize(30);
      assertThat(all.stream().filter(s -> "holdout".equals(s.split()))).hasSize(10);
    }

    @Test
    @DisplayName("题型分布：12 事实 / 8 改写 / 6 上下文 / 6 跨段 / 8 拒答")
    void tagDistribution() throws IOException {
      List<RagEvalSample> all = samples();
      assertThat(all.stream().filter(s -> s.id().startsWith("fact-"))).hasSize(12);
      assertThat(all.stream().filter(s -> s.id().startsWith("para-"))).hasSize(8);
      assertThat(all.stream().filter(s -> s.id().startsWith("ctx-"))).hasSize(6);
      assertThat(all.stream().filter(s -> s.id().startsWith("chunk-"))).hasSize(6);
      assertThat(all.stream().filter(s -> s.id().startsWith("oos-"))).hasSize(8);
    }

    @Test
    @DisplayName("生成子集固定为 10 条 dev 站内样本 + 全部 8 条拒答样本")
    void generationSubset() throws IOException {
      List<RagEvalSample> all = samples();
      long devInScope = all.stream()
          .filter(s -> s.evaluateGeneration() && !s.shouldReject()).count();
      long oos = all.stream()
          .filter(s -> s.evaluateGeneration() && s.shouldReject()).count();
      assertThat(devInScope).isEqualTo(10);
      assertThat(oos).isEqualTo(8);
    }

    @Test
    @DisplayName("样本 ID 与证据 ID 唯一，history 角色合法，非 OOS 锚点非空")
    void uniquenessAndValidity() throws IOException {
      List<RagEvalSample> all = samples();
      Set<String> ids = new HashSet<>();
      Set<String> evidenceIds = new HashSet<>();
      for (RagEvalSample s : all) {
        assertThat(ids.add(s.id())).as("样本 ID 重复: %s", s.id()).isTrue();
        for (RagEvalSample.Evidence e : s.expectedEvidence()) {
          assertThat(evidenceIds.add(e.id())).as("证据 ID 重复: %s", e.id()).isTrue();
        }
        for (RagEvalSample.HistoryMessage m : s.history()) {
          assertThat(m.role()).isIn("user", "assistant");
        }
        if (!s.shouldReject()) {
          assertThat(s.expectedEvidence()).as("非 OOS 样本锚点为空: %s", s.id()).isNotEmpty();
        } else {
          assertThat(s.expectedEvidence()).isEmpty();
        }
      }
    }

    @Test
    @DisplayName("全部锚点都能在对应 fixture 中找到")
    void anchorsExistInFixtures() throws IOException {
      List<RagEvalSample> all = samples();
      Map<String, String> fixtures = fixtures(all);
      for (RagEvalSample s : all) {
        String normalizedFixture = RagEvalSample.normalize(fixtures.get(s.fixture()));
        for (RagEvalSample.Evidence e : s.expectedEvidence()) {
          assertThat(normalizedFixture.contains(RagEvalSample.normalize(e.text())))
              .as("样本 %s 的锚点 %s 不在 %s 中", s.id(), e.id(), s.fixture())
              .isTrue();
        }
      }
    }
  }

  @Nested
  @DisplayName("语料规模与跨 Chunk 约束")
  class CorpusContract {

    @Test
    @DisplayName("每个 fixture 的 Chunk 数超过最大 Top K（20），保证 Hit@K 有区分度")
    void chunkCountExceedsMaxTopK() throws IOException {
      Map<String, String> fixtures = fixtures(samples());
      fixtures.forEach((name, content) -> {
        List<Document> chunks = splitter.apply(List.of(new Document(content)));
        assertThat(chunks.size())
            .as("fixture %s 切出 %d 个 chunk，必须大于最大 TopK %d", name, chunks.size(), MAX_TOP_K)
            .isGreaterThan(MAX_TOP_K);
      });
    }

    @Test
    @DisplayName("跨段样本的多锚点确实落在至少两个不同 Chunk")
    void chunkSamplesSpanMultipleChunks() throws IOException {
      List<RagEvalSample> all = samples();
      Map<String, String> fixtures = fixtures(all);
      for (RagEvalSample s : all) {
        if (!s.tags().contains("跨段题")) {
          continue;
        }
        List<Document> chunks = splitter.apply(
            List.of(new Document(fixtures.get(s.fixture()))));
        Set<Integer> chunkIndexes = new HashSet<>();
        for (RagEvalSample.Evidence e : s.expectedEvidence()) {
          for (int i = 0; i < chunks.size(); i++) {
            if (RagEvalSample.normalize(chunks.get(i).getText())
                .contains(RagEvalSample.normalize(e.text()))) {
              chunkIndexes.add(i);
              break;
            }
          }
        }
        assertThat(chunkIndexes.size())
            .as("跨段样本 %s 的锚点只落在 %d 个 chunk", s.id(), chunkIndexes.size())
            .isGreaterThanOrEqualTo(2);
      }
    }

    @Test
    @DisplayName("语料包含知识库外主题的同领域干扰段落（消息队列选型对比）")
    void containsHardNegativeSections() throws IOException {
      Map<String, String> fixtures = fixtures(samples());
      String javaGuide = fixtures.get("java-guide.md");
      assertThat(javaGuide).contains("消息队列选型对比");
      assertThat(javaGuide).contains("Kafka");
      assertThat(javaGuide).contains("RabbitMQ");
    }
  }
}
