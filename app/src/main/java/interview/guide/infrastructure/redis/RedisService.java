package interview.guide.infrastructure.redis;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RKeys;
import org.redisson.api.RList;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Redis 服务封装
 * 提供通用的 Redis 操作，包括缓存、分布式锁、Stream 消息队列等
 *
 * <p><b>Redisson 版本兼容说明：</b>
 * <ul>
 *   <li>当前适配 Redisson 4.0.0。</li>
 *   <li>{@code stream.readGroup} / {@code stream.autoClaim} 在空结果时可能抛出
 *       {@link ClassCastException}（Redisson 内部返回 EmptyList 而非空 Map），
 *       两处均已 catch 并做空结果处理。</li>
 *   <li>升级 Redisson 后如不再抛此异常，可清理对应的 catch 块。</li>
 * </ul>
 */
@Slf4j
@Service
public class RedisService {

    private final RedissonClient redissonClient;
    private final RedisDataAdapter dataAdapter;
    private final RedisLockAdapter lockAdapter;
    private final RedisStreamAdapter streamAdapter;

    public RedisService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.dataAdapter = new RedisDataAdapter(redissonClient);
        this.lockAdapter = new RedisLockAdapter(redissonClient);
        this.streamAdapter = new RedisStreamAdapter(redissonClient);
    }

    // ==================== 基础键值操作 ====================

    /**
     * 设置值（无过期时间）
     */
    public <T> void set(String key, T value) {
        dataAdapter.set(key, value);
    }

    /**
     * 设置值（带过期时间）
     */
    public <T> void set(String key, T value, Duration ttl) {
        dataAdapter.set(key, value, ttl);
    }

    /**
     * 获取值
     */
    public <T> T get(String key) {
        return dataAdapter.get(key);
    }

    /**
     * 获取值，如果不存在则使用 loader 加载并缓存
     */
    public <T> T getOrLoad(String key, Duration ttl, Function<String, T> loader) {
        return dataAdapter.getOrLoad(key, ttl, loader);
    }

    /**
     * 删除键
     */
    public boolean delete(String key) {
        return dataAdapter.delete(key);
    }

    /**
     * 检查键是否存在
     */
    public boolean exists(String key) {
        return dataAdapter.exists(key);
    }

    /**
     * 设置过期时间
     */
    public boolean expire(String key, Duration ttl) {
        return dataAdapter.expire(key, ttl);
    }

    /**
     * 获取剩余过期时间（毫秒）
     */
    public long getTimeToLive(String key) {
        return dataAdapter.getTimeToLive(key);
    }

    // ==================== Hash 操作 ====================

    /**
     * 设置 Hash 字段
     */
    public <K, V> void hSet(String key, K field, V value) {
        dataAdapter.hSet(key, field, value);
    }

    /**
     * 获取 Hash 字段
     */
    public <K, V> V hGet(String key, K field) {
        return dataAdapter.hGet(key, field);
    }

    /**
     * 获取整个 Hash
     */
    public <K, V> Map<K, V> hGetAll(String key) {
        return dataAdapter.hGetAll(key);
    }

    /**
     * 删除 Hash 字段
     */
    public <K, V> boolean hDelete(String key, K field) {
        return dataAdapter.hDelete(key, field);
    }

    /**
     * 检查 Hash 字段是否存在
     */
    public <K> boolean hExists(String key, K field) {
        return dataAdapter.hExists(key, field);
    }

    // ==================== 分布式锁 ====================

    /**
     * 获取锁（阻塞等待）
     */
    public RLock getLock(String lockKey) {
        return lockAdapter.getLock(lockKey);
    }

    /**
     * 尝试获取锁（非阻塞）
     */
    public boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit) {
        return lockAdapter.tryLock(lockKey, waitTime, leaseTime, unit);
    }

    /**
     * 释放锁
     */
    public void unlock(String lockKey) {
        lockAdapter.unlock(lockKey);
    }

    /**
     * 执行带锁的操作
     */
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                                  TimeUnit unit, LockedOperation<T> operation) {
        return lockAdapter.executeWithLock(lockKey, waitTime, leaseTime, unit, operation);
    }

    @FunctionalInterface
    public interface LockedOperation<T> {
        T execute();
    }

    // ==================== Stream 消息队列 ====================

    /**
     * Stream 消息处理器接口
     */
    @FunctionalInterface
    public interface StreamMessageProcessor {
        void process(StreamMessageId messageId, Map<String, String> data);
    }

    /**
     * 消费 Stream 消息（阻塞模式）
     * 使用 Redis BLOCK 参数，让服务端等待消息，比客户端轮询更高效
     *
     * @param streamKey      Stream 键
     * @param groupName      消费者组名
     * @param consumerName   消费者名
     * @param count          每次读取数量
     * @param blockTimeoutMs 阻塞等待超时时间（毫秒），0 表示无限等待
     * @param processor      消息处理器
     * @return true 如果处理了消息，false 如果超时无消息
     */
    public boolean streamConsumeMessages(
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long blockTimeoutMs,
            StreamMessageProcessor processor) {
        return streamConsumeMessages(
            streamKey,
            groupName,
            consumerName,
            count,
            blockTimeoutMs,
            0L,
            processor
        );
    }

    /**
     * 消费 Stream 消息（优先回收超时 Pending，再阻塞读取新消息）
     *
     * @param streamKey            Stream 键
     * @param groupName            消费者组名
     * @param consumerName         消费者名
     * @param count                每次读取数量
     * @param blockTimeoutMs       阻塞等待超时时间（毫秒），0 表示无限等待
     * @param pendingIdleTimeoutMs Pending 消息超过该 idle 时间后可被当前消费者回收
     * @param processor            消息处理器
     * @return true 如果处理了消息，false 如果超时无消息
     */
    public boolean streamConsumeMessages(
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long blockTimeoutMs,
            long pendingIdleTimeoutMs,
            StreamMessageProcessor processor) {
        return streamConsumeMessages(
            streamKey,
            groupName,
            consumerName,
            count,
            blockTimeoutMs,
            pendingIdleTimeoutMs,
            count,
            processor
        );
    }

    /**
     * 消费 Stream 消息（优先回收超时 Pending，再阻塞读取新消息）
     *
     * @param streamKey              Stream 键
     * @param groupName              消费者组名
     * @param consumerName           消费者名
     * @param count                  每次读取新消息数量
     * @param blockTimeoutMs         阻塞等待超时时间（毫秒），0 表示无限等待
     * @param pendingIdleTimeoutMs   Pending 消息超过该 idle 时间后可被当前消费者回收
     * @param pendingClaimBatchSize  每轮最多回收的 Pending 消息数
     * @param processor              消息处理器
     * @return true 如果处理了消息，false 如果超时无消息
     */
    public boolean streamConsumeMessages(
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long blockTimeoutMs,
            long pendingIdleTimeoutMs,
            int pendingClaimBatchSize,
            StreamMessageProcessor processor) {

        return streamAdapter.consumeMessages(
            streamKey,
            groupName,
            consumerName,
            count,
            blockTimeoutMs,
            pendingIdleTimeoutMs,
            pendingClaimBatchSize,
            processor
        );
    }

    /**
     * 创建消费者组（如果不存在）
     */
    public void createStreamGroup(String streamKey, String groupName) {
        streamAdapter.createGroup(streamKey, groupName);
    }

    /**
     * 发送消息到 Stream
     */
    public String streamAdd(String streamKey, Map<String, String> message) {
        return streamAdd(streamKey, message, 0);
    }

    /**
     * 发送消息到 Stream（带长度限制）
     *
     * @param streamKey Stream 键
     * @param message   消息内容
     * @param maxLen    最大长度，超过时自动裁剪旧消息，0 表示不限制
     * @return 消息ID
     */
    public String streamAdd(String streamKey, Map<String, String> message, int maxLen) {
        return streamAdapter.add(streamKey, message, maxLen);
    }

    /**
     * 从 Stream 读取消息（消费者组模式）
     */
    public Map<StreamMessageId, Map<String, String>> streamReadGroup(
            String streamKey, String groupName, String consumerName, int count) {
        return streamAdapter.readGroup(streamKey, groupName, consumerName, count);
    }

    /**
     * 确认消息已处理
     */
    public void streamAck(String streamKey, String groupName, StreamMessageId... ids) {
        streamAdapter.ack(streamKey, groupName, ids);
    }

    /**
     * 获取 Stream 长度
     */
    public long streamLen(String streamKey) {
        return streamAdapter.length(streamKey);
    }

    // ==================== 原子计数器 ====================

    /**
     * 获取原子计数器
     */
    public RAtomicLong getAtomicLong(String key) {
        return redissonClient.getAtomicLong(key);
    }

    /**
     * 自增并返回
     */
    public long increment(String key) {
        return redissonClient.getAtomicLong(key).incrementAndGet();
    }

    /**
     * 自减并返回
     */
    public long decrement(String key) {
        return redissonClient.getAtomicLong(key).decrementAndGet();
    }

    // ==================== 列表操作 ====================

    /**
     * 从列表右侧添加元素
     */
    public <T> void listRightPush(String key, T value) {
        RList<T> list = redissonClient.getList(key);
        list.add(value);
    }

    /**
     * 获取列表所有元素
     */
    public <T> List<T> listGetAll(String key) {
        RList<T> list = redissonClient.getList(key);
        return list.readAll();
    }

    // ==================== 工具方法 ====================

    /**
     * 获取 RedissonClient（用于高级操作）
     */
    public RedissonClient getClient() {
        return redissonClient;
    }

    /**
     * 按模式删除键
     */
    public long deleteByPattern(String pattern) {
        RKeys keys = redissonClient.getKeys();
        return keys.deleteByPattern(pattern);
    }

    /**
     * 按模式查找键
     */
    public Iterable<String> findKeysByPattern(String pattern) {
        RKeys keys = redissonClient.getKeys();
        return keys.getKeys(KeysScanOptions.defaults().pattern(pattern));
    }
}
