package interview.guide.common.async.recovery;

import interview.guide.common.async.AsyncTaskRecoveryProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 知识库向量化卡住任务恢复配置。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.async.recovery.vectorize")
public class VectorizeRecoveryProperties extends AsyncTaskRecoveryProperties {
}
