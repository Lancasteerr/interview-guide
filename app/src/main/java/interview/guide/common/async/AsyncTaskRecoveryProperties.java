package interview.guide.common.async;

import lombok.Data;

import java.time.Duration;

/**
 * 卡住任务恢复调度配置（P1-07）。两个前缀共用同一结构：
 * {@code app.async.recovery.vectorize} 与 {@code app.async.recovery.resume-analyze}。
 *
 * <p>注意：pending 阈值必须大于正常排队等待时间（消费者单线程，多个大文件排队
 * 十分钟以上是正常现象）；外部调用超时（LLM 5 分钟、S3 60 秒）必须小于
 * processing 阈值，否则请求卡住时心跳停止反而推迟恢复。
 */
@Data
public abstract class AsyncTaskRecoveryProperties {

    /**
     * 恢复调度总开关；关闭后不影响手动重试。
     */
    private boolean enabled = true;

    /**
     * PENDING 超过该时长视为投递丢失，重新补投。默认 10 分钟起。
     */
    private Duration pendingThreshold = Duration.ofMinutes(10);

    /**
     * PROCESSING 超过该时长且期间无心跳进展，条件重置回 PENDING 再补投。
     */
    private Duration processingThreshold = Duration.ofMinutes(15);

    /**
     * Embedding/LLM 阶段心跳节流间隔。
     */
    private Duration heartbeatThrottle = Duration.ofSeconds(30);

    /**
     * 调度轮询间隔（毫秒，供 @Scheduled fixedDelayString 使用）。
     */
    private long intervalMs = 60_000;

    /**
     * 每轮扫描与恢复的任务数量上限。
     */
    private int batchSize = 10;

    /**
     * 自动恢复次数上限，超过后转 FAILED，不再循环补投。
     */
    private int maxRecoveryCount = 3;
}
