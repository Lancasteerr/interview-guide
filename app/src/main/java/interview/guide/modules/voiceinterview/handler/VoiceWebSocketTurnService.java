package interview.guide.modules.voiceinterview.handler;

import io.micrometer.core.instrument.MeterRegistry;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.entity.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 语音面试单回合的 LLM、TTS 和结果下发编排器。
 *
 * <p>协议入口不参与模型调用和音频聚合，断连时仍由 Handler 控制回合线程的生命周期。</p>
 */
@Slf4j
final class VoiceWebSocketTurnService {

    private static final long AI_SPEAK_COOLDOWN_MS = 800;

    private final DashscopeLlmService llmService;
    private final QwenTtsService ttsService;
    private final VoiceInterviewProperties properties;
    private final VoiceWebSocketMessageService messageService;
    private final VoiceWebSocketConversationService conversationService;
    private final ExecutorService pipelineExecutor;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final Function<byte[], byte[]> pcmToWav;

    VoiceWebSocketTurnService(
        DashscopeLlmService llmService,
        QwenTtsService ttsService,
        VoiceInterviewProperties properties,
        VoiceWebSocketMessageService messageService,
        VoiceWebSocketConversationService conversationService,
        ExecutorService pipelineExecutor,
        ObjectProvider<MeterRegistry> meterRegistryProvider,
        Function<byte[], byte[]> pcmToWav) {
        this.llmService = llmService;
        this.ttsService = ttsService;
        this.properties = properties;
        this.messageService = messageService;
        this.conversationService = conversationService;
        this.pipelineExecutor = pipelineExecutor;
        this.meterRegistryProvider = meterRegistryProvider;
        this.pcmToWav = pcmToWav;
    }

    void process(String sessionId, WebSocketSession session, VoiceInterviewWebSocketHandler.SessionState state) {
        long turnStartNanos = System.nanoTime();
        state.setAiSpeaking(true);
        try {
            if (!session.isOpen()) {
                log.warn("WebSocket session is closed, skipping LLM response for session {}", sessionId);
                return;
            }

            String userText = state.getAccumulatedText();
            if (userText == null || userText.trim().isEmpty()) {
                log.warn("Empty user text, skipping LLM response");
                return;
            }

            log.info("Getting LLM response for session {}, textLength={}", sessionId, userText.length());

            VoiceInterviewSessionEntity sessionEntity = conversationService.getSessionEntity(sessionId);
            if (sessionEntity == null) {
                log.error("Session entity not found for session {}, cannot generate LLM response", sessionId);
                messageService.sendError(session, "会话不存在，请重新开始面试");
                return;
            }

            List<String> conversationHistory = conversationService.getHistory(
                sessionId,
                sessionEntity.getLlmProvider()
            );

            long llmStartNanos = System.nanoTime();
            AtomicLong firstTokenAtNanos = new AtomicLong(0);
            boolean streamEnabled = properties.isLlmStreamingEnabled();
            String aiReply;

            if (streamEnabled) {
                Semaphore ttsSemaphore = new Semaphore(
                    Math.max(1, properties.getMaxConcurrentTtsPerSession()));
                boolean chunkedEnabled = properties.isChunkedAudioEnabled();
                long ttsTimeoutSec = Math.max(5, properties.getTtsTimeoutSeconds());
                VoiceWebSocketTtsChunkEmitter chunkEmitter = chunkedEnabled
                    ? new VoiceWebSocketTtsChunkEmitter(
                        sessionId,
                        session,
                        ttsSemaphore,
                        ttsTimeoutSec,
                        ttsService,
                        messageService,
                        pipelineExecutor,
                        pcmToWav
                    )
                    : null;
                List<CompletableFuture<byte[]>> ttsFutures = new ArrayList<>();

                aiReply = llmService.chatStreamSentences(
                    userText,
                    partialText -> {
                        if (partialText == null || partialText.isBlank() || !session.isOpen()) {
                            return;
                        }
                        if (firstTokenAtNanos.compareAndSet(0L, System.nanoTime())) {
                            recordTimerSinceNanos(
                                "app.voice.interview.llm.first_token_latency",
                                llmStartNanos,
                                "status", "success"
                            );
                        }
                        messageService.sendTextMessage(session, partialText, false);
                    },
                    sentence -> {
                        if (sentence == null || sentence.isBlank()) {
                            return;
                        }
                        if (chunkEmitter != null) {
                            chunkEmitter.submit(sentence);
                            return;
                        }
                        ttsSemaphore.acquireUninterruptibly();
                        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                            try {
                                return ttsService.synthesize(sentence);
                            } finally {
                                ttsSemaphore.release();
                            }
                        }, pipelineExecutor);
                        ttsFutures.add(future);
                    },
                    sessionEntity,
                    conversationHistory
                );

                recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "true");
                log.info("LLM response for session {}: replyLength={}", sessionId, aiReply.length());

                if (!session.isOpen()) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId);
                    return;
                }

                messageService.sendSubtitle(session, userText, true);
                messageService.sendTextMessage(session, aiReply, true);
                conversationService.saveMessage(sessionId, userText, aiReply);

                if (chunkEmitter != null) {
                    long ttsStartNanos = System.nanoTime();
                    chunkEmitter.finish();
                    int emittedChunks = chunkEmitter.awaitCompletion();
                    recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");
                    if (emittedChunks == 0 && session.isOpen()) {
                        log.info("[Session: {}] Streaming TTS produced no chunks, falling back to full-text TTS",
                            sessionId);
                        try {
                            byte[] fallbackPcm = ttsService.synthesize(aiReply);
                            if (fallbackPcm != null && fallbackPcm.length > 0) {
                                messageService.sendAudio(session, pcmToWav.apply(fallbackPcm), aiReply);
                            }
                        } catch (Exception e) {
                            log.warn("[Session: {}] Fallback TTS failed: {}", sessionId, e.getMessage());
                        }
                    }
                } else if (!ttsFutures.isEmpty()) {
                    collectSentenceAudio(sessionId, session, aiReply, ttsFutures, ttsTimeoutSec);
                }
            } else {
                aiReply = llmService.chat(userText, sessionEntity, conversationHistory);
                recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "false");
                log.info("LLM response for session {}: replyLength={}", sessionId, aiReply.length());

                if (!session.isOpen()) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId);
                    return;
                }

                messageService.sendSubtitle(session, userText, true);
                messageService.sendTextMessage(session, aiReply, true);
                conversationService.saveMessage(sessionId, userText, aiReply);

                long ttsStartNanos = System.nanoTime();
                log.info("[Session: {}] Starting TTS synthesis for text (length: {})", sessionId, aiReply.length());
                byte[] aiAudio = ttsService.synthesize(aiReply);
                recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

                if (!session.isOpen()) {
                    return;
                }

                if (aiAudio == null || aiAudio.length == 0) {
                    log.error("[Session: {}] TTS returned empty audio", sessionId);
                    incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
                } else {
                    messageService.sendAudio(session, pcmToWav.apply(aiAudio), aiReply);
                }
            }

            state.setAccumulatedText("");
            recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "success");
            incrementCounter("app.voice.interview.turn.completed", "status", "success");

        } catch (Exception e) {
            log.error("Error triggering LLM response for session {}", sessionId, e);
            recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "failure");
            incrementCounter("app.voice.interview.turn.completed", "status", "failure");
            incrementCounter("app.voice.interview.errors", "stage", "turn");
            if (session.isOpen()) {
                messageService.sendError(session, "AI响应失败: " + e.getMessage());
            }
        } finally {
            state.setAiSpeaking(false);
            state.markAiSpeakEnd(AI_SPEAK_COOLDOWN_MS);
        }
    }

    private void collectSentenceAudio(
        String sessionId,
        WebSocketSession session,
        String aiReply,
        List<CompletableFuture<byte[]>> ttsFutures,
        long ttsTimeoutSec) {
        long ttsStartNanos = System.nanoTime();
        List<byte[]> pcmChunks = new ArrayList<>();
        int totalSize = 0;
        int failedCount = 0;
        boolean audioSentByFallback = false;
        for (CompletableFuture<byte[]> future : ttsFutures) {
            try {
                byte[] pcm = future.get(ttsTimeoutSec, TimeUnit.SECONDS);
                if (pcm != null && pcm.length > 0) {
                    pcmChunks.add(pcm);
                    totalSize += pcm.length;
                }
            } catch (Exception e) {
                future.cancel(true);
                failedCount++;
                log.warn("[Session: {}] TTS future failed for one sentence: {}", sessionId, e.getMessage());
            }
        }
        recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

        if (!session.isOpen()) {
            log.warn("WebSocket closed during TTS processing, discarding audio for session {}", sessionId);
            return;
        }

        if (totalSize == 0 && failedCount > 0 && session.isOpen()) {
            log.info("[Session: {}] All {} sentence TTS calls failed, falling back to full-text TTS",
                sessionId, failedCount);
            try {
                byte[] fallbackPcm = ttsService.synthesize(aiReply);
                if (fallbackPcm != null && fallbackPcm.length > 0) {
                    byte[] wavAudio = pcmToWav.apply(fallbackPcm);
                    log.info("[Session: {}] Fallback TTS succeeded, WAV size: {} bytes", sessionId, wavAudio.length);
                    messageService.sendAudio(session, wavAudio, aiReply);
                    audioSentByFallback = true;
                }
            } catch (Exception e) {
                log.warn("[Session: {}] Fallback TTS also failed: {}", sessionId, e.getMessage());
            }
        }

        if (!audioSentByFallback) {
            if (totalSize > 0 && session.isOpen()) {
                byte[] mergedPcm = new byte[totalSize];
                int offset = 0;
                for (byte[] chunk : pcmChunks) {
                    System.arraycopy(chunk, 0, mergedPcm, offset, chunk.length);
                    offset += chunk.length;
                }
                byte[] wavAudio = pcmToWav.apply(mergedPcm);
                log.info("[Session: {}] Sending merged audio - {} sentences, WAV size: {} bytes",
                    sessionId, pcmChunks.size(), wavAudio.length);
                messageService.sendAudio(session, wavAudio, aiReply);
            } else {
                log.error("[Session: {}] All TTS calls returned empty audio", sessionId);
                incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
            }
        }
    }

    private void recordTimerSinceNanos(String metricName, long startNanos, String... tags) {
        getRegistry().timer(metricName, tags).record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private void incrementCounter(String metricName, String... tags) {
        getRegistry().counter(metricName, tags).increment();
    }

    private MeterRegistry getRegistry() {
        return meterRegistryProvider.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new);
    }
}
