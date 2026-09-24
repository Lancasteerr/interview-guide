package interview.guide.infrastructure.redis;

import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * Redis KV 和 Hash 数据结构适配器。
 */
final class RedisDataAdapter {

  private final RedissonClient redissonClient;

  RedisDataAdapter(RedissonClient redissonClient) {
    this.redissonClient = redissonClient;
  }

  <T> void set(String key, T value) {
    bucket(key).set(value);
  }

  <T> void set(String key, T value, Duration ttl) {
    bucket(key).set(value, ttl);
  }

  <T> T get(String key) {
    RBucket<T> bucket = bucket(key);
    return bucket.get();
  }

  <T> T getOrLoad(String key, Duration ttl, Function<String, T> loader) {
    RBucket<T> bucket = bucket(key);
    T value = bucket.get();
    if (value == null) {
      value = loader.apply(key);
      if (value != null) {
        bucket.set(value, ttl);
      }
    }
    return value;
  }

  boolean delete(String key) {
    return bucket(key).delete();
  }

  boolean exists(String key) {
    return bucket(key).isExists();
  }

  boolean expire(String key, Duration ttl) {
    return bucket(key).expire(ttl);
  }

  long getTimeToLive(String key) {
    return bucket(key).remainTimeToLive();
  }

  <K, V> void hSet(String key, K field, V value) {
    map(key).put(field, value);
  }

  <K, V> V hGet(String key, K field) {
    RMap<K, V> map = map(key);
    return map.get(field);
  }

  <K, V> Map<K, V> hGetAll(String key) {
    RMap<K, V> map = map(key);
    return map.readAllMap();
  }

  <K, V> boolean hDelete(String key, K field) {
    return map(key).remove(field) != null;
  }

  <K> boolean hExists(String key, K field) {
    RMap<K, Object> map = redissonClient.getMap(key);
    return map.containsKey(field);
  }

  private <T> RBucket<T> bucket(String key) {
    return redissonClient.getBucket(key);
  }

  private <K, V> RMap<K, V> map(String key) {
    return redissonClient.getMap(key);
  }
}
