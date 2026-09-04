package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 向量化任务生产者
 * 负责发送向量化任务到 Redis Stream
 */
@Slf4j
@Component
public class VectorizeStreamProducer extends AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload> {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    record VectorizeTaskPayload(Long kbId) {}

    public VectorizeStreamProducer(RedisService redisService, KnowledgeBaseRepository knowledgeBaseRepository) {
        super(redisService);
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 发送向量化任务到 Redis Stream
     *
     * @param kbId 知识库ID
     */
    public boolean sendVectorizeTask(Long kbId) {
        return sendTask(new VectorizeTaskPayload(kbId));
    }

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(VectorizeTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(VectorizeTaskPayload payload) {
        return "kbId=" + payload.kbId();
    }

    @Override
    protected void onSendFailed(VectorizeTaskPayload payload, String error) {
        // 条件更新：不覆盖已被其他路径改写的终态（如 COMPLETED）
        knowledgeBaseRepository.failVectorUnlessCompleted(
            payload.kbId(), truncateError(error), java.time.LocalDateTime.now());
    }
}
