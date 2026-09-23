package interview.guide.common.ai.rerank;

import org.springframework.ai.document.Document;

import java.util.List;

public record RerankResult(
    List<RerankedDocument> documents,
    RerankStatus status,
    RerankReason reason,
    long durationMs
) {

  public RerankResult {
    documents = List.copyOf(documents);
  }

  public static RerankResult disabled(List<Document> candidates) {
    return unchanged(candidates, RerankStatus.DISABLED, RerankReason.DISABLED, 0);
  }

  public static RerankResult skipped(List<Document> candidates, RerankReason reason) {
    return unchanged(candidates, RerankStatus.SKIPPED, reason, 0);
  }

  public static RerankResult fallback(
      List<Document> candidates, RerankReason reason, long durationMs) {
    return unchanged(candidates, RerankStatus.FALLBACK, reason, durationMs);
  }

  public static RerankResult success(List<RerankedDocument> documents, long durationMs) {
    return new RerankResult(documents, RerankStatus.SUCCESS, RerankReason.NONE, durationMs);
  }

  private static RerankResult unchanged(
      List<Document> candidates, RerankStatus status, RerankReason reason, long durationMs) {
    List<RerankedDocument> unchanged = candidates.stream()
        .map(document -> new RerankedDocument(document, null))
        .toList();
    return new RerankResult(unchanged, status, reason, durationMs);
  }
}
