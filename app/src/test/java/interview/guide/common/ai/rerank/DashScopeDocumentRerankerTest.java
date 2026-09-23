package interview.guide.common.ai.rerank;

import interview.guide.common.config.RerankProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

@DisplayName("DashScope Rerank 客户端")
class DashScopeDocumentRerankerTest {

  private static final URI ENDPOINT = URI.create("https://llm-test.example/rerank");

  @Test
  @DisplayName("按响应分数排序并通过索引区分重复文本")
  void reranksByResponseIndex() {
    RestClient.Builder builder = RestClient.builder()
        .defaultHeader("Authorization", "Bearer test-key");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    DashScopeDocumentReranker reranker = new DashScopeDocumentReranker(
        builder.build(), ENDPOINT, DashScopeDocumentReranker.SUPPORTED_MODEL, "qa instruction");
    server.expect(requestTo(ENDPOINT))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-key"))
        .andExpect(content().string(containsString("\"model\":\"qwen3.7-text-rerank\"")))
        .andExpect(content().string(containsString("\"query\":\"问题\"")))
        .andExpect(content().string(containsString("\"documents\":[\"重复文本\",\"重复文本\",\"第三段\"]")))
        .andExpect(content().string(containsString("\"top_n\":3")))
        .andExpect(content().string(containsString("\"instruct\":\"qa instruction\"")))
        .andRespond(withSuccess("""
            {"output":{"results":[
              {"index":1,"relevance_score":0.9},
              {"index":2,"relevance_score":0.5},
              {"index":0,"relevance_score":0.1}
            ]}}
            """, MediaType.APPLICATION_JSON));

    List<Document> candidates = List.of(
        doc("first", "重复文本"), doc("second", "重复文本"), doc("third", "第三段"));
    RerankResult result = reranker.rerank("问题", candidates);

    assertThat(result.status()).isEqualTo(RerankStatus.SUCCESS);
    assertThat(result.documents()).extracting(item -> item.document().getId())
        .containsExactly("second", "third", "first");
    assertThat(result.documents()).extracting(RerankedDocument::rerankScore)
        .containsExactly(0.9, 0.5, 0.1);
    server.verify();
  }

  @Test
  @DisplayName("同分时保持原向量顺序")
  void preservesOriginalOrderForTies() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    DashScopeDocumentReranker reranker = new DashScopeDocumentReranker(
        builder.build(), ENDPOINT, DashScopeDocumentReranker.SUPPORTED_MODEL, null);
    server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
        {"output":{"results":[
          {"index":2,"relevance_score":0.7},
          {"index":0,"relevance_score":0.7},
          {"index":1,"relevance_score":0.7}
        ]}}
        """, MediaType.APPLICATION_JSON));

    RerankResult result = reranker.rerank("问题", List.of(
        doc("a", "A"), doc("b", "B"), doc("c", "C")));

    assertThat(result.documents()).extracting(item -> item.document().getId())
        .containsExactly("a", "b", "c");
  }

  @Test
  @DisplayName("响应索引不完整时整体回退")
  void fallsBackOnInvalidResponse() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    DashScopeDocumentReranker reranker = new DashScopeDocumentReranker(
        builder.build(), ENDPOINT, DashScopeDocumentReranker.SUPPORTED_MODEL, null);
    server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
        {"output":{"results":[
          {"index":0,"relevance_score":0.8},
          {"index":0,"relevance_score":0.7}
        ]}}
        """, MediaType.APPLICATION_JSON));
    List<Document> candidates = List.of(doc("a", "A"), doc("b", "B"));

    RerankResult result = reranker.rerank("问题", candidates);

    assertThat(result.status()).isEqualTo(RerankStatus.FALLBACK);
    assertThat(result.reason()).isEqualTo(RerankReason.INVALID_RESPONSE);
    assertThat(result.documents()).extracting(item -> item.document().getId())
        .containsExactly("a", "b");
    assertThat(result.documents()).extracting(RerankedDocument::rerankScore)
        .containsOnlyNulls();
  }

  @Test
  @DisplayName("HTTP 429 时保持原序并标记 4xx")
  void fallsBackOnRateLimit() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    DashScopeDocumentReranker reranker = new DashScopeDocumentReranker(
        builder.build(), ENDPOINT, DashScopeDocumentReranker.SUPPORTED_MODEL, null);
    server.expect(requestTo(ENDPOINT)).andRespond(withTooManyRequests());

    RerankResult result = reranker.rerank(
        "问题", List.of(doc("a", "A"), doc("b", "B")));

    assertThat(result.status()).isEqualTo(RerankStatus.FALLBACK);
    assertThat(result.reason()).isEqualTo(RerankReason.HTTP_4XX);
  }

  @Test
  @DisplayName("HTTP 5xx 时保持原序并标记 5xx")
  void fallsBackOnServerError() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    DashScopeDocumentReranker reranker = new DashScopeDocumentReranker(
        builder.build(), ENDPOINT, DashScopeDocumentReranker.SUPPORTED_MODEL, null);
    server.expect(requestTo(ENDPOINT)).andRespond(withServerError());

    RerankResult result = reranker.rerank(
        "问题", List.of(doc("a", "A"), doc("b", "B")));

    assertThat(result.status()).isEqualTo(RerankStatus.FALLBACK);
    assertThat(result.reason()).isEqualTo(RerankReason.HTTP_5XX);
    assertThat(result.documents()).extracting(item -> item.document().getId())
        .containsExactly("a", "b");
  }

  @Test
  @DisplayName("空响应结果不允许静默丢文档")
  void fallsBackOnEmptyResults() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    DashScopeDocumentReranker reranker = new DashScopeDocumentReranker(
        builder.build(), ENDPOINT, DashScopeDocumentReranker.SUPPORTED_MODEL, null);
    server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
        "{\"output\":{\"results\":[]}}", MediaType.APPLICATION_JSON));

    RerankResult result = reranker.rerank(
        "问题", List.of(doc("a", "A"), doc("b", "B")));

    assertThat(result.reason()).isEqualTo(RerankReason.INVALID_RESPONSE);
    assertThat(result.documents()).extracting(item -> item.document().getId())
        .containsExactly("a", "b");
  }

  @Test
  @DisplayName("空或单候选不发远程请求")
  void skipsInsufficientCandidates() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    DashScopeDocumentReranker reranker = new DashScopeDocumentReranker(
        builder.build(), ENDPOINT, DashScopeDocumentReranker.SUPPORTED_MODEL, null);

    assertThat(reranker.rerank("问题", List.of()).reason())
        .isEqualTo(RerankReason.INSUFFICIENT_CANDIDATES);
    assertThat(reranker.rerank("问题", List.of(doc("a", "A"))).status())
        .isEqualTo(RerankStatus.SKIPPED);
    server.verify();
  }

  @Test
  @DisplayName("非法 Workspace ID 在构造客户端前被拒绝")
  void rejectsInvalidWorkspaceId() {
    assertThatThrownBy(() -> DashScopeDocumentReranker.create(
        "key", "llm-valid.evil.example", DashScopeDocumentReranker.SUPPORTED_MODEL,
        new RerankProperties()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Workspace ID");
  }

  @Test
  @DisplayName("识别嵌套 HTTP 超时异常")
  void recognizesTimeoutCause() {
    ResourceAccessException error = new ResourceAccessException(
        "request failed", new HttpTimeoutException("timed out"));

    assertThat(DashScopeDocumentReranker.isTimeout(error)).isTrue();
  }

  private Document doc(String id, String text) {
    return Document.builder().id(id).text(text).build();
  }
}
