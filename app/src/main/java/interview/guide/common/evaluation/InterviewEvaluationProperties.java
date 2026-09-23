package interview.guide.common.evaluation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview.evaluation")
public class InterviewEvaluationProperties {

    private int batchSize = 8;
    /**
     * 批次失败后按问答组二分重试的总开关；关闭即恢复整批降级行为。
     */
    private boolean fallbackSplitEnabled = true;
    /**
     * 一次评估共享的额外模型调用预算（只计批次重试，不计首轮与二次汇总）。
     */
    private int fallbackMaxExtraCalls = 6;
    /**
     * 可二分拆分的最小组数；小于该值按不可拆处理（整段再试一次）。
     */
    private int fallbackMinGroups = 2;
    private String systemPromptPath = "classpath:prompts/interview/interview-evaluation-system.st";
    private String userPromptPath = "classpath:prompts/interview/interview-evaluation-user.st";
    private String summarySystemPromptPath = "classpath:prompts/interview/interview-evaluation-summary-system.st";
    private String summaryUserPromptPath = "classpath:prompts/interview/interview-evaluation-summary-user.st";
}
