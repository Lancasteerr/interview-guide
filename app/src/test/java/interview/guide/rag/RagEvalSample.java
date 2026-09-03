package interview.guide.rag;

import java.util.List;
import java.util.Map;

/**
 * RAG 测评样本（dataset-v1.jsonl 的一行）。
 */
public record RagEvalSample(
    String id,
    String question,
    List<HistoryMessage> history,
    String fixture,
    List<Evidence> expectedEvidence,
    boolean shouldReject,
    List<String> tags,
    String split,
    boolean evaluateGeneration
) {

  public record HistoryMessage(String role, String content) {
  }

  public record Evidence(String id, String text) {
  }

  @SuppressWarnings("unchecked")
  public static RagEvalSample fromMap(Map<String, Object> raw) {
    List<HistoryMessage> history = ((List<Map<String, Object>>) raw.getOrDefault("history", List.of()))
        .stream()
        .map(m -> new HistoryMessage((String) m.get("role"), (String) m.get("content")))
        .toList();
    List<Evidence> evidence = ((List<Map<String, Object>>) raw.getOrDefault("expectedEvidence", List.of()))
        .stream()
        .map(m -> new Evidence((String) m.get("id"), (String) m.get("text")))
        .toList();
    return new RagEvalSample(
        (String) raw.get("id"),
        (String) raw.get("question"),
        history,
        (String) raw.get("fixture"),
        evidence,
        Boolean.TRUE.equals(raw.get("shouldReject")),
        (List<String>) raw.getOrDefault("tags", List.of()),
        (String) raw.get("split"),
        Boolean.TRUE.equals(raw.get("evaluateGeneration")));
  }

  /**
   * 证据与片段的匹配归一化：去掉全部空白与常见 Markdown 标记后做包含判断。
   */
  public static String normalize(String text) {
    if (text == null) {
      return "";
    }
    return text
        .replaceAll("\\s+", "")
        .replace("*", "")
        .replace("#", "")
        .replace("`", "")
        .replace(">", "")
        .toLowerCase(java.util.Locale.ROOT);
  }
}
