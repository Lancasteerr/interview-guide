package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumeParseService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 简历分析 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行 AI 分析
 */
@Slf4j
@Component
public class AnalyzeStreamConsumer extends AbstractStreamConsumer<AnalyzeStreamConsumer.AnalyzePayload> {

    private final ResumeGradingService gradingService;
    private final ResumePersistenceService persistenceService;
    private final ResumeRepository resumeRepository;
    private final ResumeParseService parseService;

    public AnalyzeStreamConsumer(
        RedisService redisService,
        ResumeGradingService gradingService,
        ResumePersistenceService persistenceService,
        ResumeRepository resumeRepository,
        ResumeParseService parseService
    ) {
        super(redisService);
        this.gradingService = gradingService;
        this.persistenceService = persistenceService;
        this.resumeRepository = resumeRepository;
        this.parseService = parseService;
    }

    record AnalyzePayload(Long resumeId) {}

    @Override
    protected String taskDisplayName() {
        return "简历分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "analyze-consumer";
    }

    @Override
    protected AnalyzePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String resumeIdStr = data.get(AsyncTaskStreamConstants.FIELD_RESUME_ID);
        if (resumeIdStr == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        // 兼容旧格式消息：其中的 content 字段直接忽略，正文以数据库实体为事实来源
        return new AnalyzePayload(Long.parseLong(resumeIdStr));
    }

    @Override
    protected String payloadIdentifier(AnalyzePayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    @Override
    protected boolean shouldSkip(AnalyzePayload payload) {
        return resumeRepository.findById(payload.resumeId())
            .map(resume -> resume.getAnalyzeStatus() == AsyncTaskStatus.COMPLETED)
            .orElse(true);
    }

    @Override
    protected void markProcessing(AnalyzePayload payload) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(AnalyzePayload payload) {
        Long resumeId = payload.resumeId();
        ResumeEntity resume = resumeRepository.findById(resumeId).orElse(null);
        if (resume == null) {
            log.warn("简历已被删除，跳过分析任务: resumeId={}", resumeId);
            return;
        }

        String resumeText = resume.getResumeText();
        if (isBlank(resumeText)) {
            // 历史数据正文为空时，从 RustFS 恢复文本并以独立短事务回填后再分析
            resumeText = parseService.downloadAndParseContent(
                resume.getStorageKey(), resume.getOriginalFilename());
            if (isBlank(resumeText)) {
                throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法获取简历文本内容");
            }
            persistenceService.updateResumeText(resumeId, resumeText);
        }

        ResumeAnalysisResponse analysis = gradingService.analyzeResume(resumeText);
        ResumeEntity latestResume = resumeRepository.findById(resumeId).orElse(null);
        if (latestResume == null) {
            log.warn("简历在分析期间被删除，跳过保存结果: resumeId={}", resumeId);
            return;
        }
        persistenceService.saveAnalysis(latestResume, analysis);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    protected void markCompleted(AnalyzePayload payload) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(AnalyzePayload payload, String error) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, error);
    }

    @Override
    protected void retryMessage(AnalyzePayload payload, int retryCount) {
        Long resumeId = payload.resumeId();
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_RESUME_ID, resumeId.toString(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("简历分析任务已重新入队: resumeId={}, retryCount={}", resumeId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: resumeId={}, error={}", resumeId, e.getMessage(), e);
            updateAnalyzeStatus(resumeId, AsyncTaskStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
        }
    }

    /**
     * 更新分析状态
     */
    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
        try {
            resumeRepository.findById(resumeId).ifPresent(resume -> {
                resume.setAnalyzeStatus(status);
                resume.setAnalyzeError(error);
                resumeRepository.save(resume);
                log.debug("分析状态已更新: resumeId={}, status={}", resumeId, status);
            });
        } catch (Exception e) {
            log.error("更新分析状态失败: resumeId={}, status={}, error={}", resumeId, status, e.getMessage(), e);
        }
    }

}
