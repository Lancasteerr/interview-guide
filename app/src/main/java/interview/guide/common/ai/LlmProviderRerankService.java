package interview.guide.common.ai;

import interview.guide.common.ai.rerank.DashScopeDocumentReranker;
import interview.guide.common.ai.rerank.DocumentReranker;
import interview.guide.common.ai.rerank.RerankExecutionMode;
import interview.guide.common.ai.rerank.RerankReason;
import interview.guide.common.ai.rerank.RerankResult;
import interview.guide.common.config.RerankProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider Rerank 客户端解析、缓存和失败回退。
 */
@Slf4j
final class LlmProviderRerankService {

  private final LlmProviderResolver providerResolver;
  private final RerankProperties rerankProperties;
  private final Map<String, RerankerResolution> rerankerCache = new ConcurrentHashMap<>();

  LlmProviderRerankService(
      LlmProviderResolver providerResolver, RerankProperties rerankProperties) {
    this.providerResolver = providerResolver;
    this.rerankProperties = rerankProperties;
  }

  RerankResult rerankDocuments(
      String query, List<Document> candidates, RerankExecutionMode mode) {
    if (mode == RerankExecutionMode.DISABLED
        || (mode == RerankExecutionMode.CONFIGURED && !rerankProperties.isEnabled())) {
      return RerankResult.disabled(candidates);
    }
    if (candidates.size() < 2) {
      return RerankResult.skipped(candidates, RerankReason.INSUFFICIENT_CANDIDATES);
    }
    RerankerResolution resolution = rerankerCache.computeIfAbsent(
        "dashscope", this::resolveReranker);
    if (resolution.reranker() == null) {
      return RerankResult.skipped(candidates, resolution.reason());
    }
    try {
      return resolution.reranker().rerank(query, candidates);
    } catch (Exception e) {
      log.warn("RAG Rerank 回退: model={}, candidates={}, status=fallback, reason={}, durationMs={}",
          DashScopeDocumentReranker.SUPPORTED_MODEL, candidates.size(),
          RerankReason.CLIENT_ERROR.metricValue(), 0);
      return RerankResult.fallback(candidates, RerankReason.CLIENT_ERROR, 0);
    }
  }

  int cacheSize() {
    return rerankerCache.size();
  }

  void clearCache() {
    rerankerCache.clear();
  }

  private RerankerResolution resolveReranker(String providerId) {
    LlmProviderResolver.ProviderSnapshot config;
    try {
      config = providerResolver.loadProviderOrThrow(providerId);
    } catch (Exception e) {
      log.warn("[LlmProviderRegistry] Reranker skipped: provider={}, reason={}",
          providerId, RerankReason.NOT_CONFIGURED.metricValue());
      return new RerankerResolution(null, RerankReason.NOT_CONFIGURED);
    }
    if (!config.supportsRerank() || providerResolver.isBlank(config.rerankModel())
        || providerResolver.isBlank(config.rerankWorkspaceId())
        || providerResolver.isBlank(config.apiKey())) {
      log.warn("[LlmProviderRegistry] Reranker skipped: provider={}, reason={}",
          providerId, RerankReason.NOT_CONFIGURED.metricValue());
      return new RerankerResolution(null, RerankReason.NOT_CONFIGURED);
    }
    if (!DashScopeDocumentReranker.SUPPORTED_MODEL.equals(config.rerankModel())) {
      log.warn("[LlmProviderRegistry] Reranker skipped: provider={}, model={}, reason={}",
          providerId, config.rerankModel(), RerankReason.UNSUPPORTED_MODEL.metricValue());
      return new RerankerResolution(null, RerankReason.UNSUPPORTED_MODEL);
    }
    try {
      DocumentReranker reranker = DashScopeDocumentReranker.create(
          config.apiKey(), config.rerankWorkspaceId(), config.rerankModel(), rerankProperties);
      log.info("[LlmProviderRegistry] Reranker created: provider={}, model={}",
          providerId, config.rerankModel());
      return new RerankerResolution(reranker, RerankReason.NONE);
    } catch (IllegalArgumentException e) {
      log.warn("[LlmProviderRegistry] Reranker skipped: provider={}, model={}, reason={}",
          providerId, config.rerankModel(), RerankReason.NOT_CONFIGURED.metricValue());
      return new RerankerResolution(null, RerankReason.NOT_CONFIGURED);
    }
  }

  private record RerankerResolution(DocumentReranker reranker, RerankReason reason) {
  }
}
