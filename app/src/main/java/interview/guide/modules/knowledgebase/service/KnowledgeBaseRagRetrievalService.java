package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.rerank.RerankResult;
import interview.guide.common.ai.rerank.RerankedDocument;
import interview.guide.common.ai.rerank.RerankExecutionMode;
import interview.guide.modules.knowledgebase.metrics.RagMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 负责 RAG 候选召回、双路融合和重排。
 *
 * <p>查询门面只负责业务流程和响应编排，具体的候选集处理集中在此处，
 * 以便向量检索策略可以独立测试和回滚。</p>
 */
final class KnowledgeBaseRagRetrievalService {

  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseQueryService.class);

  private final LlmProviderRegistry llmProviderRegistry;
  private final RagMetrics ragMetrics;
  private final KnowledgeBaseVectorService vectorService;
  private final boolean mergeOriginalQuery;

  KnowledgeBaseRagRetrievalService(
      LlmProviderRegistry llmProviderRegistry,
      RagMetrics ragMetrics,
      KnowledgeBaseVectorService vectorService,
      boolean mergeOriginalQuery) {
    this.llmProviderRegistry = llmProviderRegistry;
    this.ragMetrics = ragMetrics;
    this.vectorService = vectorService;
    this.mergeOriginalQuery = mergeOriginalQuery;
  }

  List<Document> retrieve(
      KnowledgeBaseRagQueryPlan queryPlan,
      List<Long> knowledgeBaseIds,
      List<String> attemptedQueries) {
    List<String> candidates = queryPlan.candidateQueries();
    if (mergeOriginalQuery && candidates.size() > 1
        && !candidates.get(0).equals(candidates.get(1))) {
      return retrieveAndMerge(queryPlan, knowledgeBaseIds, attemptedQueries);
    }

    for (int i = 0; i < candidates.size(); i++) {
      String candidateQuery = candidates.get(i);
      if (candidateQuery.isBlank()) {
        continue;
      }
      if (attemptedQueries != null) {
        attemptedQueries.add(candidateQuery);
      }
      long startNanos = System.nanoTime();
      List<Document> documents = vectorService.similaritySearch(
          candidateQuery,
          knowledgeBaseIds,
          queryPlan.searchParams().topK(),
          queryPlan.searchParams().minScore());
      long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
      LOG.info("RAG 检索完成: kbCount={}, questionLength={}, queryVariant={}, hits={}, durationMs={}",
          knowledgeBaseIds.size(), candidateQuery.length(),
          i == 0 ? "rewritten" : "original", documents.size(), durationMs);
      if (hasEffectiveHit(documents)) {
        ragMetrics.recordRetrievalHits("single", documents.size());
        return documents;
      }
    }
    return List.of();
  }

  RerankResult rerank(String query, List<Document> candidates) {
    return rerank(query, candidates, RerankExecutionMode.CONFIGURED);
  }

  RerankResult rerank(
      String query,
      List<Document> candidates,
      RerankExecutionMode mode) {
    RerankResult result = mode == RerankExecutionMode.CONFIGURED
        ? llmProviderRegistry.rerankDocuments(query, candidates)
        : llmProviderRegistry.rerankDocuments(query, candidates, mode);
    ragMetrics.recordStageDuration(
        "rerank", result.status().name().toLowerCase(), result.durationMs() * 1_000_000L);
    ragMetrics.recordRerankRequest(
        result.status().name().toLowerCase(), result.reason().metricValue());
    return result;
  }

  List<Document> documentsOf(RerankResult result) {
    return result.documents().stream().map(RerankedDocument::document).toList();
  }

  private List<Document> retrieveAndMerge(
      KnowledgeBaseRagQueryPlan queryPlan,
      List<Long> knowledgeBaseIds,
      List<String> attemptedQueries) {
    Map<String, Document> merged = new LinkedHashMap<>();
    int topK = queryPlan.searchParams().topK();
    int rewrittenHits = 0;
    int originalHits = 0;

    for (int i = 0; i < 2; i++) {
      String candidateQuery = queryPlan.candidateQueries().get(i);
      if (candidateQuery.isBlank()) {
        continue;
      }
      if (attemptedQueries != null) {
        attemptedQueries.add(candidateQuery);
      }
      List<Document> documents = vectorService.similaritySearch(
          candidateQuery, knowledgeBaseIds, topK, queryPlan.searchParams().minScore());
      if (i == 0) {
        rewrittenHits = documents.size();
      } else {
        originalHits = documents.size();
      }
      for (Document document : documents) {
        merged.merge(document.getId(), document, KnowledgeBaseRagRetrievalService::higherScore);
      }
    }

    List<Document> result = merged.values().stream()
        .sorted(KnowledgeBaseRagRetrievalService::scoreDescendingNullsLast)
        .limit(topK)
        .collect(Collectors.toList());
    LOG.info("RAG 双路融合完成: kbCount={}, rewrittenHits={}, originalHits={}, dedupedHits={}, "
            + "finalHits={}, topK={}",
        knowledgeBaseIds.size(), rewrittenHits, originalHits, merged.size(), result.size(), topK);
    ragMetrics.recordRetrievalHits("rewritten", rewrittenHits);
    ragMetrics.recordRetrievalHits("original", originalHits);
    ragMetrics.recordRetrievalHits("merged", result.size());
    return result;
  }

  private boolean hasEffectiveHit(List<Document> documents) {
    return documents != null && !documents.isEmpty();
  }

  private static Document higherScore(Document existing, Document incoming) {
    Double existingScore = existing.getScore();
    Double incomingScore = incoming.getScore();
    if (incomingScore == null) {
      return existing;
    }
    if (existingScore == null || incomingScore > existingScore) {
      return incoming;
    }
    return existing;
  }

  private static int scoreDescendingNullsLast(Document first, Document second) {
    Double firstScore = first.getScore();
    Double secondScore = second.getScore();
    if (firstScore == null && secondScore == null) {
      return 0;
    }
    if (firstScore == null) {
      return 1;
    }
    if (secondScore == null) {
      return -1;
    }
    return Double.compare(secondScore, firstScore);
  }
}
