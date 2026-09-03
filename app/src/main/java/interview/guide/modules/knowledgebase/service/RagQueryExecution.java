package interview.guide.modules.knowledgebase.service;

import java.util.List;
import java.util.Map;

/**
 * 一次 RAG 查询的完整执行轨迹。
 *
 * <p>供 RAG 测评（P1-01）与后续可观测性（P1-04）使用：公开业务入口的行为不变，
 * 只有显式传入收集器的重载入口会填充该结构。
 *
 * @param originalQuestion   原始问题（已 trim）
 * @param rewrittenQuestion  改写后的问题（未开启改写或改写失败时与原始问题相同）
 * @param attemptedQueries   实际尝试的候选 Query，按尝试顺序排列
 * @param resolvedTopK       本次解析出的 Top K（按问题长度动态分档）
 * @param resolvedMinScore   本次解析出的相似度阈值
 * @param retrievedDocs      最终采用的检索片段，按排名排列
 * @param rewriteDurationMs  Query 改写耗时
 * @param retrievalDurationMs 检索耗时（含全部候选 Query）
 * @param generationDurationMs 生成耗时
 * @param answer             最终答案（含流式归一化后的拒答模板）
 * @param outcome            ANSWERED / NO_RESULT / ERROR
 */
public record RagQueryExecution(
    String originalQuestion,
    String rewrittenQuestion,
    List<String> attemptedQueries,
    int resolvedTopK,
    double resolvedMinScore,
    List<RetrievedDoc> retrievedDocs,
    long rewriteDurationMs,
    long retrievalDurationMs,
    long generationDurationMs,
    String answer,
    String outcome
) {

  /**
   * 检索片段快照。
   *
   * @param rank     片段排名（1 起）
   * @param text     片段全文
   * @param score    相似度分数（来自向量库 metadata，可能为 null）
   * @param metadata 片段 metadata（含 kb_id 等）
   */
  public record RetrievedDoc(int rank, String text, Double score, Map<String, Object> metadata) {
  }
}
