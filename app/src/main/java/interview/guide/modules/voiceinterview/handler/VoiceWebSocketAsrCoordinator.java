package interview.guide.modules.voiceinterview.handler;

import interview.guide.modules.voiceinterview.service.QwenAsrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketSession;

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
