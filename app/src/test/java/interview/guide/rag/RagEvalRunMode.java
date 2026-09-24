package interview.guide.rag;

import java.util.Locale;

enum RagEvalRunMode {
  BASELINE("baseline"),
  RERANK("rerank"),
  PAIRED_RERANK("paired-rerank");

  private final String value;

  RagEvalRunMode(String value) {
    this.value = value;
  }

  static RagEvalRunMode fromEnvironment() {
    String raw = System.getenv().getOrDefault("RAG_EVAL_MODE", BASELINE.value);
    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    for (RagEvalRunMode mode : values()) {
      if (mode.value.equals(normalized)) {
        return mode;
      }
    }
    throw new IllegalArgumentException(
        "RAG_EVAL_MODE 只支持 baseline、rerank 或 paired-rerank，实际值: " + raw);
  }

  String value() {
    return value;
  }
}
