package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Qwen ASR 会话注册表，集中处理并发会话、会话锁和连接状态。
 */
final class QwenAsrSessionManager {

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

  Object lockFor(String sessionId) {
    return sessionLocks.computeIfAbsent(sessionId, key -> new Object());
  }

  boolean contains(String sessionId) {
    return sessions.containsKey(sessionId);
  }

  Session get(String sessionId) {
    return sessions.get(sessionId);
  }

  void put(String sessionId, Session session) {
    sessions.put(sessionId, session);
  }

  Session remove(String sessionId) {
    return sessions.remove(sessionId);
  }

  void removeLock(String sessionId) {
    sessionLocks.remove(sessionId);
  }

  void removeIfSameConversation(String sessionId, OmniRealtimeConversation conversation) {
    sessions.compute(sessionId, (id, existing) -> {
      if (existing != null && existing.getConversation() == conversation) {
        return null;
      }
      return existing;
    });
  }

  Set<String> sessionIds() {
    return Set.copyOf(sessions.keySet());
  }

  int size() {
    return sessions.size();
  }

  void clear() {
    sessions.clear();
    sessionLocks.clear();
  }

  static final class Session {
    private final OmniRealtimeConversation conversation;
    @SuppressWarnings("unused")
    private final Consumer<String> onFinal;
    @SuppressWarnings("unused")
    private final Consumer<String> onPartial;
    @SuppressWarnings("unused")
    private final Consumer<Throwable> onError;
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    Session(
        OmniRealtimeConversation conversation,
        Consumer<String> onFinal,
        Consumer<String> onPartial,
        Consumer<Throwable> onError) {
      this.conversation = conversation;
      this.onFinal = onFinal;
      this.onPartial = onPartial;
      this.onError = onError;
    }

    OmniRealtimeConversation getConversation() {
      return conversation;
    }

    void markReady() {
      readyLatch.countDown();
    }

    boolean isReady() {
      return readyLatch.getCount() == 0;
    }

    boolean awaitReady(long timeoutMs) throws InterruptedException {
      return readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }
  }
}
