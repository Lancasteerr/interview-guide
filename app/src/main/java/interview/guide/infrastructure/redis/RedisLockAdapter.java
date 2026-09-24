package interview.guide.infrastructure.redis;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

/**
 * Redis 分布式锁适配器。
 */
final class RedisLockAdapter {

    private final RedissonClient redissonClient;

    RedisLockAdapter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    RLock getLock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }

    boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            return lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void unlock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                          TimeUnit unit, RedisService.LockedOperation<T> operation) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(waitTime, leaseTime, unit)) {
                try {
                    return operation.execute();
                } finally {
                    lock.unlock();
                }
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取锁失败: " + lockKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取锁被中断: " + lockKey, e);
        }
    }
}
