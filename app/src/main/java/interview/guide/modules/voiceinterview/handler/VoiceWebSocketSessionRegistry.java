package interview.guide.modules.voiceinterview.handler;

import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 管理语音 WebSocket 的连接、会话状态和活动时间，隔离并发 Map 操作。
 */
final class VoiceWebSocketSessionRegistry {

  private final Map<String, WebSocketSession> sessions;
  private final Map<String, VoiceInterviewWebSocketHandler.SessionState> sessionStates;
  private final Map<String, Long> lastActivityTime;

  VoiceWebSocketSessionRegistry(
      Map<String, WebSocketSession> sessions,
      Map<String, VoiceInterviewWebSocketHandler.SessionState> sessionStates,
      Map<String, Long> lastActivityTime) {
    this.sessions = sessions;
    this.sessionStates = sessionStates;
    this.lastActivityTime = lastActivityTime;
  }

  void register(String sessionId, WebSocketSession session) {
    sessions.put(sessionId, session);
    sessionStates.put(sessionId, new VoiceInterviewWebSocketHandler.SessionState());
    touch(sessionId);
  }

  WebSocketSession getSession(String sessionId) {
    return sessions.get(sessionId);
  }

  VoiceInterviewWebSocketHandler.SessionState getState(String sessionId) {
    return sessionStates.get(sessionId);
  }

  void touch(String sessionId) {
    lastActivityTime.put(sessionId, System.currentTimeMillis());
  }

  VoiceInterviewWebSocketHandler.SessionState remove(String sessionId) {
    sessions.remove(sessionId);
    lastActivityTime.remove(sessionId);
    return sessionStates.remove(sessionId);
  }

  void forEachLastActivity(BiConsumer<String, Long> consumer) {
    lastActivityTime.forEach(consumer);
  }
}
