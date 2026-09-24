package interview.guide.infrastructure.redis;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RKeys;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

import java.util.List;

/**
 * Redis 原子计数、List 和 Pattern 查询适配器。
 */
final class RedisCollectionAdapter {

  private final RedissonClient redissonClient;

  RedisCollectionAdapter(RedissonClient redissonClient) {
    this.redissonClient = redissonClient;
  }

  RAtomicLong getAtomicLong(String key) {
    return redissonClient.getAtomicLong(key);
  }

  long increment(String key) {
    return redissonClient.getAtomicLong(key).incrementAndGet();
  }

  long decrement(String key) {
    return redissonClient.getAtomicLong(key).decrementAndGet();
  }

  <T> void listRightPush(String key, T value) {
    RList<T> list = redissonClient.getList(key);
    list.add(value);
  }

  <T> List<T> listGetAll(String key) {
    RList<T> list = redissonClient.getList(key);
    return list.readAll();
  }

  long deleteByPattern(String pattern) {
    RKeys keys = redissonClient.getKeys();
    return keys.deleteByPattern(pattern);
  }

  Iterable<String> findKeysByPattern(String pattern) {
    RKeys keys = redissonClient.getKeys();
    return keys.getKeys(KeysScanOptions.defaults().pattern(pattern));
  }
}
