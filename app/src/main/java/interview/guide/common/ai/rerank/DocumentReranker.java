package interview.guide.common.ai.rerank;

import org.springframework.ai.document.Document;

import java.util.List;

public interface DocumentReranker {

  RerankResult rerank(String query, List<Document> candidates);
}
