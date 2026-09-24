package interview.guide.modules.knowledgebase.service;

import java.util.List;

/** RAG 查询阶段之间传递的不可变计划。 */
record KnowledgeBaseRagQueryPlan(
    String originalQuestion,
    List<String> candidateQueries,
    KnowledgeBaseRagSearchParams searchParams,
    long rewriteDurationMs) {
}

/** RAG 检索参数，避免在查询链路中散落多个独立参数。 */
record KnowledgeBaseRagSearchParams(int topK, double minScore) {
}
