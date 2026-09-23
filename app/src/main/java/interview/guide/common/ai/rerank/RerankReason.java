package interview.guide.common.ai.rerank;

public enum RerankReason {
  NONE("none"),
  DISABLED("disabled"),
  NOT_CONFIGURED("not_configured"),
  UNSUPPORTED_MODEL("unsupported_model"),
  INSUFFICIENT_CANDIDATES("insufficient_candidates"),
  TIMEOUT("timeout"),
  HTTP_4XX("http_4xx"),
  HTTP_5XX("http_5xx"),
  INVALID_RESPONSE("invalid_response"),
  CLIENT_ERROR("client_error");

  private final String metricValue;

  RerankReason(String metricValue) {
    this.metricValue = metricValue;
  }

  public String metricValue() {
    return metricValue;
  }
}
