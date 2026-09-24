package interview.guide.infrastructure.redis;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.AutoClaimResult;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Redis Stream 适配器。
 *
 * <p>保留原有 Pending 回收和 Redisson 空结果兼容逻辑，避免把协议细节泄漏到业务服务。</p>
 */
@Slf4j
final class RedisStreamAdapter {

    private final RedissonClient redissonClient;
    private final ConcurrentMap<String, StreamMessageId> reclaimCursors = new ConcurrentHashMap<>();

    RedisStreamAdapter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    boolean consumeMessages(
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long blockTimeoutMs,
            long pendingIdleTimeoutMs,
            int pendingClaimBatchSize,
            RedisService.StreamMessageProcessor processor) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        Map<StreamMessageId, Map<String, String>> messages = reclaimPendingMessages(
            stream,
            streamKey,
            groupName,
            consumerName,
            pendingClaimBatchSize,
            pendingIdleTimeoutMs
        );
        if (processMessages(messages, processor)) {
            return true;
        }

        try {
            messages = stream.readGroup(
                groupName,
                consumerName,
                StreamReadGroupArgs.neverDelivered()
                    .count(count)
                    .timeout(Duration.ofMillis(blockTimeoutMs))
            );
        } catch (ClassCastException e) {
            log.debug("Redisson 4.0.0 内部类型转换异常（空结果时触发），等价于本批无消息: stream={}, group={}",
                streamKey, groupName);
            return false;
        }

        return processMessages(messages, processor);
    }

    private Map<StreamMessageId, Map<String, String>> reclaimPendingMessages(
            RStream<String, String> stream,
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long pendingIdleTimeoutMs) {
        if (pendingIdleTimeoutMs <= 0 || count <= 0) {
            return Map.of();
        }

        String cursorKey = streamKey + ":" + groupName;
        StreamMessageId startId = reclaimCursors.getOrDefault(cursorKey, StreamMessageId.MIN);
        AutoClaimResult<String, String> result;
        try {
            result = stream.autoClaim(
                groupName,
                consumerName,
                pendingIdleTimeoutMs,
                TimeUnit.MILLISECONDS,
                startId,
                count
            );
        } catch (ClassCastException e) {
            log.debug("Redisson 4.0.0 内部类型转换异常（无可回收消息）: stream={}, group={}",
                streamKey, groupName);
            return Map.of();
        }

        StreamMessageId nextId = result.getNextId();
        if (nextId == null || StreamMessageId.MIN.equals(nextId)) {
            reclaimCursors.remove(cursorKey);
        } else {
            reclaimCursors.put(cursorKey, nextId);
        }

        List<StreamMessageId> deletedIds = result.getDeletedIds();
        if (deletedIds != null && !deletedIds.isEmpty()) {
            log.warn("Stream pending messages were trimmed before reclaim: stream={}, group={}, ids={}",
                streamKey, groupName, deletedIds);
        }

        Map<StreamMessageId, Map<String, String>> messages = result.getMessages();
        if (messages != null && !messages.isEmpty()) {
            log.info("Reclaimed Redis Stream pending messages: stream={}, group={}, consumer={}, count={}",
                streamKey, groupName, consumerName, messages.size());
        }
        return messages == null ? Map.of() : messages;
    }

    private boolean processMessages(
            Map<StreamMessageId, Map<String, String>> messages,
            RedisService.StreamMessageProcessor processor) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
            processor.process(entry.getKey(), entry.getValue());
        }
        return true;
    }

    void createGroup(String streamKey, String groupName) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        try {
            stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream());
            log.info("创建 Stream 消费者组: stream={}, group={}", streamKey, groupName);
        } catch (Exception e) {
            if (e instanceof org.redisson.client.RedisException
                    && e.getMessage() != null
                    && e.getMessage().contains("BUSYGROUP")) {
                return;
            }
            log.warn("创建消费者组失败: stream={}, group={}, error={}", streamKey, groupName, e.getMessage());
        }
    }

    String add(String streamKey, Map<String, String> message, int maxLen) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        StreamAddArgs<String, String> args = StreamAddArgs.entries(message);
        if (maxLen > 0) {
            args.trimNonStrict().maxLen(maxLen);
        }
        StreamMessageId messageId = stream.add(args);
        log.debug("发送 Stream 消息: stream={}, messageId={}, maxLen={}", streamKey, messageId, maxLen);
        return messageId.toString();
    }

    Map<StreamMessageId, Map<String, String>> readGroup(
            String streamKey, String groupName, String consumerName, int count) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        return stream.readGroup(groupName, consumerName,
            StreamReadGroupArgs.neverDelivered().count(count));
    }

    void ack(String streamKey, String groupName, StreamMessageId... ids) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        stream.ack(groupName, ids);
    }

    long length(String streamKey) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        return stream.size();
    }
}
