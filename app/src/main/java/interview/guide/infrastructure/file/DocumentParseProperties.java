package interview.guide.infrastructure.file;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 文档解析的超时与有界线程池配置。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.document-parse")
public class DocumentParseProperties {

  @NotNull
  private Duration timeout = Duration.ofMinutes(2);

  @Min(1)
  @Max(16)
  private int poolSize = 2;

  @Min(1)
  private int queueCapacity = 20;
}
