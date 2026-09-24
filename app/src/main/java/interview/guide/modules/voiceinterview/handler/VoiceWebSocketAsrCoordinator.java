package interview.guide.modules.voiceinterview.handler;

import interview.guide.modules.voiceinterview.service.QwenAsrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketSession;

import java.util.Base64;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 负责语音 WebSocket 的 ASR 会话建立、就绪检查和自动重连。
 */
@Slf4j
final class VoiceWebSocketAsrCoordinator {

    private static final int MAX_ASR_READY_RETRY = 2;
    private static final long ASR_READY_CHECK_DELAY_SECONDS = 10;

    private final QwenAsrService sttService;
    private final VoiceWebSocketMessageService messageService;
    private final VoiceWebSocketSessionRegistry sessionRegistry;
    private final ScheduledExecutorService scheduler;

    VoiceWebSocketAsrCoordinator(
        QwenAsrService sttService,
        VoiceWebSocketMessageService messageService,
        VoiceWebSocketSessionRegistry sessionRegistry,
        ScheduledExecutorService scheduler) {
        this.sttService = sttService;
        this.messageService = messageService;
        this.sessionRegistry = sessionRegistry;
        this.scheduler = scheduler;
    }

    void start(
        String sessionId,
        WebSocketSession session,
        BiConsumer<String, Boolean> sttResultHandler) {
        sttService.startTranscription(
            sessionId,
            text -> sttResultHandler.accept(text, true),
            text -> sttResultHandler.accept(text, false),
            () -> messageService.sendAsrReady(session),
            error -> {
                log.error("STT error for session {}", sessionId, error);
                messageService.sendError(session, "语音识别失败: " + error.getMessage());
            }
        );

        scheduleReadyCheck(sessionId, session, sttResultHandler, 0);
    }

    void restart(String sessionId, BiConsumer<String, Boolean> sttResultHandler) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        sttService.restartTranscription(
            sessionId,
            text -> sttResultHandler.accept(text, true),
            text -> sttResultHandler.accept(text, false),
            () -> messageService.sendAsrReady(session),
            error -> {
                log.error("STT error for session {}", sessionId, error);
                messageService.sendError(session, "语音识别失败: " + error.getMessage());
            }
        );
    }

    void sendAudio(
        String sessionId,
        String base64Audio,
        BiConsumer<String, Boolean> sttResultHandler) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        VoiceInterviewWebSocketHandler.SessionState state = sessionRegistry.getState(sessionId);
        if (state != null && state.isAiSpeakingOrCooldown()) {
            return;
        }

        try {
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            log.debug("Received audio data for session {}, size: {} bytes", sessionId, audioData.length);

            try {
                sttService.sendAudio(sessionId, audioData);
            } catch (IllegalStateException ex) {
                if (isAsrNotReady(ex)) {
                    log.debug("[Session: {}] Dropping audio chunk before ASR ready", sessionId);
                    return;
                }
                if (shouldRecoverAsrConnection(ex)) {
                    log.warn("[Session: {}] ASR send failed ({}), restarting DashScope and retrying chunk",
                        sessionId, ex.getMessage() != null ? ex.getMessage() : "unknown");
                    restart(sessionId, sttResultHandler);
                    boolean sent = false;
                    for (int i = 0; i < 15; i++) {
                        try {
                            Thread.sleep(80);
                            sttService.sendAudio(sessionId, audioData);
                            sent = true;
                            break;
                        } catch (IllegalStateException retry) {
                            if (isAsrNotReady(retry)) {
                                continue;
                            }
                            if (!shouldRecoverAsrConnection(retry)) {
                                throw retry;
                            }
                        }
                    }
                    if (!sent) {
                        log.error("[Session: {}] ASR still down after restart", sessionId);
                        messageService.sendError(session, "语音识别连接中断，请刷新页面后重试");
                    }
                } else {
                    throw ex;
                }
            }
        } catch (Exception e) {
            log.error("Error handling user audio for session {}", sessionId, e);
            messageService.sendError(session, formatErrorMessage(e));
        }
    }

    private static boolean shouldRecoverAsrConnection(IllegalStateException ex) {
        String message = ex.getMessage();
        return message != null
            && (message.contains("No active session") || message.contains("ASR append failed"));
    }

    private static boolean isAsrNotReady(IllegalStateException ex) {
        String message = ex.getMessage();
        return message != null && message.contains("ASR session not ready");
    }

    private static String formatErrorMessage(Exception e) {
        Throwable cause = e.getCause();
        if (cause != null) {
            String message = cause.getMessage();
            if (message != null) {
                if (message.contains("403") || message.contains("ACCESS_DENIED")) {
                    return "阿里云语音服务认证失败：AccessKey 无效或已过期。请在 .env 文件中配置正确的 ALIYUN_ACCESS_KEY";
                }
                if (message.contains("timeout") || message.contains("channel inactive")) {
                    return "阿里云语音服务连接超时。请检查网络连接或稍后重试";
                }
            }
        }
        return "语音处理失败：" + e.getMessage();
    }

    private void scheduleReadyCheck(
        String sessionId,
        WebSocketSession session,
        BiConsumer<String, Boolean> sttResultHandler,
        int retryCount) {
        scheduler.schedule(
            () -> checkReadyOrRetry(sessionId, session, sttResultHandler, retryCount),
            ASR_READY_CHECK_DELAY_SECONDS,
            TimeUnit.SECONDS
        );
    }

    private void checkReadyOrRetry(
        String sessionId,
        WebSocketSession session,
        BiConsumer<String, Boolean> sttResultHandler,
        int retryCount) {
        if (session == null || !session.isOpen() || sttService.isReady(sessionId)) {
            return;
        }

        if (retryCount < MAX_ASR_READY_RETRY) {
            int nextRetry = retryCount + 1;
            log.warn("[Session: {}] ASR not ready after {}s, retrying ({}/{})",
                sessionId, ASR_READY_CHECK_DELAY_SECONDS, nextRetry, MAX_ASR_READY_RETRY);
            messageService.sendAsrStatus(session, "asr_reconnecting", "语音识别连接较慢，正在自动重连");
            restart(sessionId, sttResultHandler);
            scheduleReadyCheck(sessionId, session, sttResultHandler, nextRetry);
            return;
        }

        log.warn("[Session: {}] ASR still not ready after {} retries", sessionId, retryCount);
        messageService.sendError(session, "语音识别连接准备超时，请检查语音服务配置或稍后重试");
    }
}
