package interview.guide.modules.voiceinterview.handler;

import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 负责语音 WebSocket 的不活跃检测、暂停通知和资源清理。
 */
@Slf4j
final class VoiceWebSocketTimeoutService {

    private static final long WARNING_TIME_MS = (long) (4.5 * 60 * 1000);
    private static final long PAUSE_TIMEOUT_MS = 5 * 60 * 1000;

    private final VoiceWebSocketSessionRegistry sessionRegistry;
    private final VoiceWebSocketMessageService messageService;
    private final VoiceInterviewService interviewService;
    private final QwenAsrService sttService;

    VoiceWebSocketTimeoutService(
        VoiceWebSocketSessionRegistry sessionRegistry,
        VoiceWebSocketMessageService messageService,
        VoiceInterviewService interviewService,
        QwenAsrService sttService) {
        this.sessionRegistry = sessionRegistry;
        this.messageService = messageService;
        this.interviewService = interviewService;
        this.sttService = sttService;
    }

    void checkPauseTimeout() {
        long now = System.currentTimeMillis();

        sessionRegistry.forEachLastActivity((sessionId, lastTime) -> {
            long elapsed = now - lastTime;

            if (elapsed > WARNING_TIME_MS && elapsed < PAUSE_TIMEOUT_MS) {
                sendPauseWarning(sessionId);
            } else if (elapsed >= PAUSE_TIMEOUT_MS) {
                log.warn("Session {} inactive for {} minutes, pausing",
                    sessionId, PAUSE_TIMEOUT_MS / 60000);
                handlePauseTimeout(sessionId);
            }
        });
    }

    void cleanupStaleSessions() {
        try {
            int cleaned = interviewService.cleanupStaleSessions();
            if (cleaned > 0) {
                log.info("Stale session cleanup: {} sessions cleaned", cleaned);
            }
        } catch (Exception e) {
            log.error("Error during stale session cleanup", e);
        }
    }

    private void sendPauseWarning(String sessionId) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session != null && session.isOpen()) {
            messageService.sendControl(
                session,
                "pause_timeout_warning",
                "会话将在30秒后暂停，请继续说话或点击继续"
            );
        }
    }

    private void handlePauseTimeout(String sessionId) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);

        try {
            if (session != null && session.isOpen()) {
                messageService.sendControl(
                    session,
                    "pause_timeout",
                    "会话因超时已暂停,可在历史记录中恢复"
                );
            }

            interviewService.pauseSession(sessionId, "timeout");

            if (session != null && session.isOpen()) {
                session.close(CloseStatus.GOING_AWAY);
            }

            sttService.stopTranscription(sessionId);
            sessionRegistry.remove(sessionId);
            log.info("Session {} paused due to timeout", sessionId);
        } catch (Exception e) {
            log.error("Error handling pause timeout for session {}", sessionId, e);
        }
    }
}
