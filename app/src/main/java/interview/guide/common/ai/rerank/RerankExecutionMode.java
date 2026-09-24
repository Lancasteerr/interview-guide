package interview.guide.common.ai.rerank;

/**
 * Controls how a RAG request invokes the configured reranker.
 *
 * <p>{@link #CONFIGURED} is the production default and follows the global
 * {@code app.ai.rag.rerank.enabled} switch. The other two modes are explicit
 * invocation modes used by retrieval evaluation so that two arms can run in
 * one JVM without mutating Spring configuration state.</p>
 */
public enum RerankExecutionMode {
  CONFIGURED,
  DISABLED,
  FORCE_ENABLED
}
