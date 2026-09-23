package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag.rerank")
public class RerankProperties {

  private boolean enabled = false;
  private Duration connectTimeout = Duration.ofSeconds(2);
  private Duration readTimeout = Duration.ofSeconds(5);
  private String instruction =
      "Given a web search query, retrieve relevant passages that answer the query.";
}
