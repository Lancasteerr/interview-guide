package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Qwen ASR 实时连接、重连、音频发送和清理适配器。
 */
@Slf4j
final class QwenAsrConnectionService {

    private final QwenAsrConfiguration configuration;
    private final QwenAsrSessionManager sessionManager;
    private final QwenAsrEventService eventService;

    QwenAsrConnectionService(
            QwenAsrConfiguration configuration,
            QwenAsrSessionManager sessionManager,
            QwenAsrEventService eventService) {
        this.configuration = configuration;
        this.sessionManager = sessionManager;
        this.eventService = eventService;
    }

    void start(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
        synchronized (sessionManager.lockFor(sessionId)) {
            startLocked(sessionId, onFinal, onPartial, onReady, onError);
        }
    }

    void restart(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
        synchronized (sessionManager.lockFor(sessionId)) {
            log.info("[Session: {}] Restarting DashScope ASR (stop + start)", sessionId);
            stop(sessionId);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            startLocked(sessionId, onFinal, onPartial, onReady, onError);

            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    Thread.sleep(100);
                    QwenAsrSessionManager.Session newSession = sessionManager.get(sessionId);
                    if (newSession != null && newSession.isReady()) {
                        log.info("[Session: {}] ASR reconnection verified successfully", sessionId);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[Session: {}] ASR reconnection verification interrupted", sessionId);
                    return;
                }
            }

            log.warn("[Session: {}] ASR reconnection may not be fully ready after 1 second", sessionId);
        }
    }

    private void startLocked(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
        if (sessionManager.contains(sessionId)) {
            throw new IllegalStateException("Session already exists: " + sessionId);
        }

        try {
            OmniRealtimeParam param = OmniRealtimeParam.builder()
                    .model(configuration.model())
                    .url(configuration.url())
                    .apikey(configuration.apiKey())
                    .build();

            AtomicReference<OmniRealtimeConversation> conversationRef = new AtomicReference<>();
            OmniRealtimeCallback callback = new OmniRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("[Session: {}] WebSocket connection established", sessionId);
                }

                @Override
                public void onEvent(JsonObject message) {
                    eventService.handleServerEvent(sessionId, message, onFinal, onPartial, onError);
                }

                @Override
                public void onClose(int code, String reason) {
                    OmniRealtimeConversation closed = conversationRef.get();
                    log.warn("[Session: {}] DashScope ASR WebSocket closed - code: {}, reason: {}",
                            sessionId, code, reason);
                    if (closed != null) {
                        sessionManager.removeIfSameConversation(sessionId, closed);
                    }
                }
            };

            OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, callback);
            conversationRef.set(conversation);
            QwenAsrSessionManager.Session asrSession = new QwenAsrSessionManager.Session(
                    conversation, onFinal, onPartial, onError);
            sessionManager.put(sessionId, asrSession);

            Thread connectionThread = new Thread(() -> {
                try {
                    conversation.connect();

                    OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
                    transcriptionParam.setLanguage(configuration.language());
                    transcriptionParam.setInputSampleRate(configuration.sampleRate());
                    transcriptionParam.setInputAudioFormat(configuration.format());

                    OmniRealtimeConfig config = OmniRealtimeConfig.builder()
                            .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
                            .enableTurnDetection(configuration.enableTurnDetection())
                            .turnDetectionType(configuration.turnDetectionType())
                            .turnDetectionThreshold(configuration.turnDetectionThreshold())
                            .turnDetectionSilenceDurationMs(configuration.turnDetectionSilenceDurationMs())
                            .transcriptionConfig(transcriptionParam)
                            .build();

                    conversation.updateSession(config);
                    if (sessionManager.get(sessionId) != asrSession) {
                        log.debug("[Session: {}] Ignoring stale ASR connection ready callback", sessionId);
                        return;
                    }
                    asrSession.markReady();
                    if (onReady != null) {
                        onReady.run();
                    }
                    log.info("[Session: {}] Transcription session started successfully", sessionId);
                } catch (Exception e) {
                    log.error("[Session: {}] Failed to establish connection", sessionId, e);
                    sessionManager.removeIfSameConversation(sessionId, conversation);
                    onError.accept(e);
                }
            }, "ASR-Connection-" + sessionId);
            connectionThread.setDaemon(true);
            connectionThread.start();
        } catch (Exception e) {
            String errorMsg = "Failed to create transcription session: " + sessionId;
            log.error(errorMsg, e);
            sessionManager.remove(sessionId);
            onError.accept(new IllegalStateException(errorMsg, e));
            throw new IllegalStateException(errorMsg, e);
        }
    }

    void sendAudio(String sessionId, byte[] audioData) {
        QwenAsrSessionManager.Session session = sessionManager.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("No active session found: " + sessionId);
        }

        try {
            if (!session.awaitReady(1200)) {
                throw new IllegalStateException("ASR session not ready: " + sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR session ready wait interrupted: " + sessionId, e);
        }

        try {
            String audioBase64 = Base64.getEncoder().encodeToString(audioData);
            session.getConversation().appendAudio(audioBase64);
            log.trace("[Session: {}] Sent {} bytes of audio data", sessionId, audioData.length);
        } catch (Exception e) {
            log.error("[Session: {}] appendAudio failed (upstream may reconnect)", sessionId, e);
            throw new IllegalStateException("ASR append failed: " + sessionId, e);
        }
    }

    void stop(String sessionId) {
        synchronized (sessionManager.lockFor(sessionId)) {
            QwenAsrSessionManager.Session session = sessionManager.remove(sessionId);
            sessionManager.removeLock(sessionId);
            if (session == null) {
                log.warn("[Session: {}] Attempted to stop non-existent session", sessionId);
                return;
            }

            try {
                session.getConversation().endSession();
                log.info("[Session: {}] Transcription session stopped", sessionId);
            } catch (InterruptedException e) {
                log.error("[Session: {}] Thread interrupted while ending session", sessionId, e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[Session: {}] Error while ending session (may already be closed): {}",
                        sessionId, e.getMessage());
            }

            try {
                session.getConversation().close();
            } catch (Exception e) {
                log.debug("[Session: {}] Connection already closed: {}", sessionId, e.getMessage());
            }
        }
    }

    void destroy() {
        log.info("Destroying QwenAsrService with {} active sessions", sessionManager.size());
        Set<String> activeSessionIds = sessionManager.sessionIds();
        activeSessionIds.forEach(sessionId -> {
            try {
                stop(sessionId);
            } catch (Exception e) {
                log.error("[Session: {}] Error during cleanup", sessionId, e);
            }
        });
        sessionManager.clear();
        log.info("QwenAsrService destroyed successfully");
    }
}
