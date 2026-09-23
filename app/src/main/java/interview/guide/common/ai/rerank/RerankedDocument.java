package interview.guide.common.ai.rerank;

import org.springframework.ai.document.Document;

public record RerankedDocument(Document document, Double rerankScore) {
}
