package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.entity.VoiceInterviewSessionEntity;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;

/**
 * 语音面试会话缓存适配器，集中维护缓存键和 TTL 策略。
 */
final class VoiceInterviewSessionCacheService {

  private static final String SESSION_CACHE_KEY_PREFIX = "voice:interview:session:";
  private static final Duration CACHE_TTL = Duration.ofHours(1);

  private final RedissonClient redissonClient;

  VoiceInterviewSessionCacheService(RedissonClient redissonClient) {
    this.redissonClient = redissonClient;
  }

  VoiceInterviewSessionEntity get(Long sessionId) {
    if (sessionId == null) {
      return null;
    }
    RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(key(sessionId));
    return bucket.get();
  }

  void put(VoiceInterviewSessionEntity session) {
    RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(key(session.getId()));
    bucket.set(session, CACHE_TTL);
  }

  void invalidate(Long sessionId) {
    if (sessionId == null) {
      return;
    }
    redissonClient.getBucket(key(sessionId)).delete();
  }

  private String key(Long sessionId) {
    return SESSION_CACHE_KEY_PREFIX + sessionId;
  }
}
