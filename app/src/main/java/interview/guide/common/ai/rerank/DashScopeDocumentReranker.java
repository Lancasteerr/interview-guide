package interview.guide.common.ai.rerank;

import interview.guide.common.config.RerankProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Slf4j
public class DashScopeDocumentReranker implements DocumentReranker {

  public static final String SUPPORTED_MODEL = "qwen3.7-text-rerank";
  static final int MAX_DOCUMENTS = 500;
  private static final Pattern WORKSPACE_ID_PATTERN = Pattern.compile("[A-Za-z0-9-]+");
  private static final String ENDPOINT_TEMPLATE =
      "https://%s.cn-beijing.maas.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

  private final RestClient restClient;
  private final URI endpoint;
  private final String model;
  private final String instruction;

  public static DashScopeDocumentReranker create(
      String apiKey,
      String workspaceId,
      String model,
      RerankProperties properties) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("DashScope Rerank API Key 未配置");
    }
    if (workspaceId == null || !WORKSPACE_ID_PATTERN.matcher(workspaceId).matches()) {
      throw new IllegalArgumentException("DashScope Rerank Workspace ID 格式无效");
    }
    HttpClientSettings settings = HttpClientSettings.defaults()
        .withConnectTimeout(properties.getConnectTimeout())
        .withReadTimeout(properties.getReadTimeout());
    RestClient restClient = RestClient.builder()
        .defaultHeader("Authorization", "Bearer " + apiKey)
        .defaultHeader("Content-Type", "application/json")
        .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
        .build();
    URI endpoint = URI.create(ENDPOINT_TEMPLATE.formatted(workspaceId));
    return new DashScopeDocumentReranker(
        restClient, endpoint, model, properties.getInstruction());
  }

  DashScopeDocumentReranker(
      RestClient restClient,
      URI endpoint,
      String model,
      String instruction) {
    this.restClient = restClient;
    this.endpoint = endpoint;
    this.model = model;
    this.instruction = instruction;
  }

  @Override
  @SuppressWarnings("unchecked")
  public RerankResult rerank(String query, List<Document> candidates) {
    if (candidates.size() < 2) {
      return RerankResult.skipped(candidates, RerankReason.INSUFFICIENT_CANDIDATES);
    }
    if (candidates.size() > MAX_DOCUMENTS) {
      return RerankResult.fallback(candidates, RerankReason.CLIENT_ERROR, 0);
    }

    long start = System.nanoTime();
    try {
      Map<String, Object> response = restClient.post()
          .uri(endpoint)
          .body(buildRequest(query, candidates))
          .retrieve()
          .body(Map.class);
      List<RerankedDocument> reranked = parseResponse(response, candidates);
      long durationMs = elapsedMs(start);
      log.info("RAG Rerank 完成: model={}, candidates={}, status=success, durationMs={}",
          model, candidates.size(), durationMs);
      return RerankResult.success(reranked, durationMs);
    } catch (RestClientResponseException e) {
      RerankReason reason = e.getStatusCode().is4xxClientError()
          ? RerankReason.HTTP_4XX : RerankReason.HTTP_5XX;
      return fallback(candidates, reason, start, e);
    } catch (ResourceAccessException e) {
      RerankReason reason = isTimeout(e) ? RerankReason.TIMEOUT : RerankReason.CLIENT_ERROR;
      return fallback(candidates, reason, start, e);
    } catch (InvalidRerankResponseException e) {
      return fallback(candidates, RerankReason.INVALID_RESPONSE, start, e);
    } catch (Exception e) {
      return fallback(candidates, RerankReason.CLIENT_ERROR, start, e);
    }
  }

  private Map<String, Object> buildRequest(String query, List<Document> candidates) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("query", query);
    input.put("documents", candidates.stream().map(Document::getText).toList());
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("top_n", candidates.size());
    if (instruction != null && !instruction.isBlank()) {
      parameters.put("instruct", instruction);
    }
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("model", model);
    request.put("input", input);
    request.put("parameters", parameters);
    return request;
  }

  private List<RerankedDocument> parseResponse(
      Map<String, Object> response, List<Document> candidates) {
    if (response == null || !(response.get("output") instanceof Map<?, ?> output)
        || !(output.get("results") instanceof List<?> results)
        || results.size() != candidates.size()) {
      throw new InvalidRerankResponseException();
    }

    Set<Integer> seenIndexes = new HashSet<>();
    List<ScoredIndex> scoredIndexes = new ArrayList<>();
    for (Object item : results) {
      if (!(item instanceof Map<?, ?> result)
          || !(result.get("index") instanceof Number indexValue)
          || !(result.get("relevance_score") instanceof Number scoreValue)) {
        throw new InvalidRerankResponseException();
      }
      int index = indexValue.intValue();
      double score = scoreValue.doubleValue();
      if (index < 0 || index >= candidates.size() || !seenIndexes.add(index)
          || !Double.isFinite(score)) {
        throw new InvalidRerankResponseException();
      }
      scoredIndexes.add(new ScoredIndex(index, score));
    }
    scoredIndexes.sort(Comparator.comparingDouble(ScoredIndex::score).reversed()
        .thenComparingInt(ScoredIndex::index));
    return scoredIndexes.stream()
        .map(item -> new RerankedDocument(candidates.get(item.index()), item.score()))
        .toList();
  }

  private RerankResult fallback(
      List<Document> candidates, RerankReason reason, long start, Exception error) {
    long durationMs = elapsedMs(start);
    log.warn("RAG Rerank 回退: model={}, candidates={}, status=fallback, reason={}, durationMs={}",
        model, candidates.size(), reason.metricValue(), durationMs);
    return RerankResult.fallback(candidates, reason, durationMs);
  }

  static boolean isTimeout(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof HttpTimeoutException
          || current instanceof SocketTimeoutException
          || current instanceof TimeoutException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private long elapsedMs(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  private record ScoredIndex(int index, double score) {
  }

  private static final class InvalidRerankResponseException extends RuntimeException {
  }
}
