package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import interview.guide.modules.voiceinterview.dto.WebSocketControlMessage;
import interview.guide.modules.voiceinterview.entity.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.context.VoiceContextCompressor;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket Handler for Voice Interview
 * 语音面试 WebSocket 处理器
 * <p>
 * Handles real-time bidirectional audio streaming for voice interviews.
 * Processing pipeline: User Audio → STT → LLM → TTS → AI Audio
 * </p>
 */
@Component
@Slf4j
public class VoiceInterviewWebSocketHandler extends TextWebSocketHandler implements DisposableBean {

    private final ObjectMapper objectMapper;
    private final QwenAsrService sttService;
    private final QwenTtsService ttsService;
    private final DashscopeLlmService llmService;
    private final VoiceInterviewService interviewService;
    private final VoiceContextCompressor voiceContextCompressor;
    private final interview.guide.modules.voiceinterview.context.VoiceHistoryLoader voiceHistoryLoader;
    private final VoiceInterviewProperties voiceInterviewProperties;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final VoiceWebSocketMessageService messageService;
    private final VoiceWebSocketConversationService conversationService;
    private final VoiceWebSocketTimeoutService timeoutService;
    private final VoiceWebSocketAsrCoordinator asrCoordinator;
    private final VoiceWebSocketTurnService turnService;

    VoiceInterviewWebSocketHandler(
        ObjectMapper objectMapper,
        QwenAsrService sttService,
        QwenTtsService ttsService,
        DashscopeLlmService llmService,
        VoiceInterviewService interviewService,
        VoiceContextCompressor voiceContextCompressor,
        interview.guide.modules.voiceinterview.context.VoiceHistoryLoader voiceHistoryLoader,
        VoiceInterviewProperties voiceInterviewProperties,
        ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(
            objectMapper,
            sttService,
            ttsService,
            llmService,
            interviewService,
            voiceContextCompressor,
            voiceHistoryLoader,
            voiceInterviewProperties,
            meterRegistryProvider,
            new VoiceWebSocketMessageService(objectMapper),
            new VoiceWebSocketConversationService(interviewService, voiceHistoryLoader)
        );
    }

    @Autowired
    VoiceInterviewWebSocketHandler(
        ObjectMapper objectMapper,
        QwenAsrService sttService,
        QwenTtsService ttsService,
        DashscopeLlmService llmService,
        VoiceInterviewService interviewService,
        VoiceContextCompressor voiceContextCompressor,
        interview.guide.modules.voiceinterview.context.VoiceHistoryLoader voiceHistoryLoader,
        VoiceInterviewProperties voiceInterviewProperties,
        ObjectProvider<MeterRegistry> meterRegistryProvider,
        VoiceWebSocketMessageService messageService,
        VoiceWebSocketConversationService conversationService) {
        this.objectMapper = objectMapper;
        this.sttService = sttService;
        this.ttsService = ttsService;
        this.llmService = llmService;
        this.interviewService = interviewService;
        this.voiceContextCompressor = voiceContextCompressor;
        this.voiceHistoryLoader = voiceHistoryLoader;
        this.voiceInterviewProperties = voiceInterviewProperties;
        this.meterRegistryProvider = meterRegistryProvider;
        this.messageService = messageService;
        this.conversationService = conversationService;
        this.timeoutService = new VoiceWebSocketTimeoutService(
            sessionRegistry,
            messageService,
            interviewService,
            sttService
        );
        this.asrCoordinator = new VoiceWebSocketAsrCoordinator(
            sttService,
            messageService,
            sessionRegistry,
            utteranceMergeScheduler
        );
        this.openingService = new VoiceWebSocketOpeningService(
            ttsService,
            voiceInterviewProperties,
            conversationService,
            messageService,
            this::convertPcmToWav
        );
        this.turnService = new VoiceWebSocketTurnService(
            llmService,
            ttsService,
            voiceInterviewProperties,
            messageService,
            conversationService,
            voicePipelineExecutor,
            meterRegistryProvider,
            this::convertPcmToWav
        );
    }

    /**
     * 合并多段 STT 定稿后再触发 LLM 的延迟调度（与 {@link VoiceInterviewProperties#getUserUtteranceDebounceMs()} 配合）
     */
    private final ScheduledExecutorService utteranceMergeScheduler = createUtteranceMergeScheduler();

    /**
     * LLM / TTS / JDBC 等阻塞工作全部跑在虚拟线程上，避免占满 utteranceMergeScheduler 的 2 个调度线程。
     */
    private final ExecutorService voicePipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();
    private final VoiceWebSocketOpeningService openingService;

    // Activity tracking for pause timeout
    // 活动跟踪（用于暂停超时）
    private final Map<String, Long> lastActivityTime = new ConcurrentHashMap<>();
    private final VoiceWebSocketSessionRegistry sessionRegistry =
        new VoiceWebSocketSessionRegistry(sessions, sessionStates, lastActivityTime);
    private static final int WS_SEND_TIME_LIMIT_MS = 10_000;
    private static final int WS_SEND_BUFFER_LIMIT_BYTES = 512 * 1024;
    /** AI 音频播放结束后的冷却期，防止扬声器尾音被麦克风拾取触发 STT */
    private static final long AI_SPEAK_COOLDOWN_MS = 800;

    private static ScheduledExecutorService createUtteranceMergeScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "voice-utterance-merge");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    @PostConstruct
    void warmupOpeningAudioCache() {
        voicePipelineExecutor.execute(() -> {
            openingService.warmupOpeningAudioCache();
        });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);

        // Increase message size limits for audio streaming
        // 1 second of PCM audio @ 16kHz, 16-bit = ~32KB raw, ~42KB base64
        // Set limit to 256KB to allow some buffer and multiple messages
        session.setTextMessageSizeLimit(256 * 1024); // 256KB
        session.setBinaryMessageSizeLimit(256 * 1024); // 256KB

        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
            session, WS_SEND_TIME_LIMIT_MS, WS_SEND_BUFFER_LIMIT_BYTES
        );

        sessionRegistry.register(sessionId, safeSession);
        log.info("WebSocket connection established for session: {}", sessionId);

        try {
            asrCoordinator.start(
                sessionId,
                safeSession,
                (text, isFinal) -> handleSttResult(sessionId, text, isFinal)
            );

            // 发送欢迎消息
            messageService.sendMessage(safeSession, messageService.createWelcomeMessage());
            // 自动开场：面试官先说开场语并直接提出第一个问题（仅首次连接、无历史消息时触发）
            triggerOpeningQuestionIfNeeded(sessionId, safeSession);
        } catch (Exception e) {
            log.error("Error establishing WebSocket connection for session {}", sessionId, e);
            messageService.sendError(safeSession, "初始化语音识别失败: " + e.getMessage());
        }
    }

    private void triggerOpeningQuestionIfNeeded(String sessionId, WebSocketSession session) {
        voicePipelineExecutor.execute(() -> {
            openingService.sendOpeningQuestion(sessionId, session);
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = extractSessionId(session);

        try {
            JsonNode msg = objectMapper.readTree(message.getPayload());
            String type = msg.get("type").asText();
            int messageSize = message.getPayload().length();
            int messageSizeKB = messageSize / 1024;
            if ("audio".equals(type)) {
                log.trace("[WebSocket] Received audio: sessionId={}, size={}KB", sessionId, messageSizeKB);
            } else {
                log.info("[WebSocket] Received message: sessionId={}, type={}, size={}KB ({} bytes)",
                    sessionId, type, messageSizeKB, messageSize);
            }

            if (messageSizeKB > 200) {
                log.warn("[WebSocket] Large message detected: {}KB", messageSizeKB);
            }

            // Update last activity time for pause timeout detection
            // 更新最后活动时间（用于暂停超时检测）
            sessionRegistry.touch(sessionId);

            switch (type) {
                case "audio":
                    String audioData = msg.has("data") ? msg.get("data").asText() : null;
                    if (audioData != null && !audioData.isEmpty()) {
                        asrCoordinator.sendAudio(
                            sessionId,
                            audioData,
                            (text, isFinal) -> handleSttResult(sessionId, text, isFinal)
                        );
                    } else {
                        log.warn("Received audio message without data");
                    }
                    break;
                case "control":
                    try {
                        handleControl(sessionId, objectMapper.treeToValue(msg, WebSocketControlMessage.class));
                    } catch (Exception e) {
                        log.error("Error handling control message for session {}", sessionId, e);
                        messageService.sendError(session, "控制消息处理失败: " + e.getMessage());
                    }
                    break;
                default:
                    log.warn("Unknown message type: {} for session {}", type, sessionId);
            }

        } catch (Exception e) {
            log.error("Error handling message for session {}", sessionId, e);
            messageService.sendError(session, "消息处理失败: " + e.getMessage());
        }
    }

    @Override
    public void destroy() {
        voicePipelineExecutor.shutdownNow();
        utteranceMergeScheduler.shutdownNow();
        try {
            if (!voicePipelineExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("voicePipelineExecutor did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = extractSessionId(session);
        try {
            SessionState removedState = sessionRegistry.remove(sessionId);
            if (removedState != null) {
                Thread t = removedState.getProcessingThread();
                if (t != null) {
                    t.interrupt();
                }
            }
            // Stop STT transcription
            sttService.stopTranscription(sessionId);
            log.info("WebSocket connection closed for session: {}, status: {}", sessionId, status);

            // WebSocket 异常断开时自动结束会话，防止状态永远停留在 IN_PROGRESS
            try {
                interviewService.endSessionIfInProgress(sessionId);
            } catch (Exception endEx) {
                log.warn("Failed to auto-end session {} after disconnect: {}", sessionId, endEx.getMessage());
            }
        } catch (Exception e) {
            log.error("Error cleaning up session {} after close", sessionId, e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}", extractSessionId(session), exception);
    }

    /**
     * Handle STT result from callback (partial = live; final = committed segment for LLM).
     */
    private void handleSttResult(String sessionId, String recognizedText, boolean isFinalSegment) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        SessionState state = sessionRegistry.getState(sessionId);

        if (session == null || state == null) {
            log.warn("Session or state not found: {}", sessionId);
            return;
        }

        // 用户已提交或 AI 正在回答时，丢弃上一轮迟到的 partial/final，防止污染下一轮字幕。
        if (state.isProcessing().get() || state.isAiSpeakingOrCooldown()) {
            log.debug("Discarding late STT result for session {}, final={}, textLength={}",
                sessionId, isFinalSegment, recognizedText.length());
            return;
        }

        if (!isFinalSegment) {
            state.markSttActivity();
            messageService.sendSubtitle(session, state.getMergeBufferPreviewWithPartial(recognizedText), false);
            return;
        }

        log.debug("STT final segment for session {}: textLength={}", sessionId, recognizedText.length());
        incrementCounter("app.voice.interview.asr.final_segments", "status", "received");

        // 合并多次 VAD 切段，只更新实时字幕；是否提交给 LLM 由前端手动 submit 控制
        state.appendFinalSttSegment(recognizedText);
        messageService.sendSubtitle(session, state.getMergeBufferPreview(), false);
    }

    /**
     * 手动提交：获取 mergeBuffer 中累积的用户文本并触发 LLM 管线。
     */
    private void flushMergedUtteranceToLlm(String sessionId) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        SessionState state = sessionRegistry.getState(sessionId);
        if (session == null || state == null || !session.isOpen()) {
            return;
        }
        if (!state.isProcessing().compareAndSet(false, true)) {
            utteranceMergeScheduler.schedule(
                    () -> flushMergedUtteranceToLlm(sessionId),
                    400,
                    TimeUnit.MILLISECONDS);
            return;
        }
        long mergeStartAt = state.getMergeStartedAt();
        String userText = state.takeMergeBufferAndClear();
        if (userText == null || userText.trim().isEmpty()) {
            state.isProcessing().set(false);
            return;
        }
        long mergeWaitMs = Math.max(0, System.currentTimeMillis() - mergeStartAt);
        recordTimerMillis("app.voice.interview.asr.merge_wait", mergeWaitMs, "status", "success");
        state.setAccumulatedText(userText);
        log.info("Merged user utterance for session {}, triggering LLM (length {})", sessionId, userText.length());

        // 提交到虚拟线程执行阻塞的 LLM+TTS 管线，立即释放调度器线程
        voicePipelineExecutor.execute(() -> {
            state.setProcessingThread(Thread.currentThread());
            try {
                triggerLlmResponse(sessionId, session, state);
            } finally {
                state.isProcessing().set(false);
                state.setProcessingThread(null);
            }
        });
    }



    /**
     * Trigger LLM response for completed sentence.
     * When streaming is enabled, uses sentence-level TTS overlap: each detected sentence
     * triggers a concurrent TTS call, so TTS runs in parallel with the rest of LLM generation.
     */
    private void triggerLlmResponse(String sessionId, WebSocketSession session, SessionState state) {
        turnService.process(sessionId, session, state);
    }

    /**
     * Handle control message (end_interview, start_phase)
     */
    private void handleControl(String sessionId, WebSocketControlMessage control) {
        log.info("Control message for session {}: action={}, phase={}",
                sessionId, control.getAction(), control.getPhase());

        switch (control.getAction()) {
            case "submit":
                if (control.getData() != null) {
                    Object textObj = control.getData().get("text");
                    if (textObj instanceof String text && !text.isBlank()) {
                        SessionState state = sessionRegistry.getState(sessionId);
                        if (state != null) {
                            state.setMergeBufferDirectly(text);
                        }
                    }
                }
                flushMergedUtteranceToLlm(sessionId);
                break;
            case "end_interview":
                interviewService.endSession(sessionId);
                break;
            case "start_phase":
                interviewService.startPhase(sessionId, control.getPhase());
                break;
        }
    }

    private void recordTimerSinceNanos(String metricName, long startNanos, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        long elapsed = Math.max(0, System.nanoTime() - startNanos);
        registry.timer(metricName, tags).record(elapsed, TimeUnit.NANOSECONDS);
    }

    private void recordTimerMillis(String metricName, long millis, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        registry.timer(metricName, tags).record(Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    private void incrementCounter(String metricName, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        registry.counter(metricName, tags).increment();
    }

    private MeterRegistry getRegistry() {
        return meterRegistryProvider.getIfAvailable();
    }

    /**
     * Scheduled task to check for pause warnings and timeouts
     * Runs every 30 seconds
     * 定时任务：检查暂停警告和超时
     */
    @Scheduled(fixedRate = 30000)
    public void checkPauseTimeout() {
        timeoutService.checkPauseTimeout();
    }

    @Scheduled(fixedRate = 60_000)
    public void cleanupStaleSessions() {
        timeoutService.cleanupStaleSessions();
    }

    /**
     * Extract session ID from WebSocket URI path
     * Path format: /ws/voice-interview/{sessionId}
     */
    private String extractSessionId(WebSocketSession session) {
        String path = session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Convert PCM audio to WAV format
     * Adds 44-byte WAV header to PCM data for browser playback
     *
     * @param pcmData Raw PCM audio data (24kHz, 16-bit, mono)
     * @return WAV formatted audio data
     */
    private byte[] convertPcmToWav(byte[] pcmData) {
        // Use 24000Hz for Qwen TTS Realtime API
        int sampleRate = 24000;
        int bitsPerSample = 16;
        int numChannels = 1;
        int byteRate = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int fileSize = dataSize + 36;

        byte[] wavData = new byte[dataSize + 44];

        // Write WAV header directly to avoid stream allocation overhead
        int pos = 0;

        // RIFF header
        wavData[pos++] = 'R'; wavData[pos++] = 'I'; wavData[pos++] = 'F'; wavData[pos++] = 'F';
        writeIntLE(wavData, pos, fileSize); pos += 4;
        wavData[pos++] = 'W'; wavData[pos++] = 'A'; wavData[pos++] = 'V'; wavData[pos++] = 'E';

        // fmt chunk
        wavData[pos++] = 'f'; wavData[pos++] = 'm'; wavData[pos++] = 't'; wavData[pos++] = ' ';
        writeIntLE(wavData, pos, 16); pos += 4; // Chunk size
        writeShortLE(wavData, pos, (short) 1); pos += 2; // Audio format (1 = PCM)
        writeShortLE(wavData, pos, (short) numChannels); pos += 2;
        writeIntLE(wavData, pos, sampleRate); pos += 4;
        writeIntLE(wavData, pos, byteRate); pos += 4;
        writeShortLE(wavData, pos, (short) blockAlign); pos += 2;
        writeShortLE(wavData, pos, (short) bitsPerSample); pos += 2;

        // data chunk
        wavData[pos++] = 'd'; wavData[pos++] = 'a'; wavData[pos++] = 't'; wavData[pos++] = 'a';
        writeIntLE(wavData, pos, dataSize); pos += 4;

        // Copy PCM data
        System.arraycopy(pcmData, 0, wavData, 44, pcmData.length);

        return wavData;
    }

    /**
     * Write 32-bit integer in little-endian format
     */
    private static void writeIntLE(byte[] buf, int pos, int value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
        buf[pos + 2] = (byte) ((value >> 16) & 0xFF);
        buf[pos + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * Write 16-bit short in little-endian format
     */
    private static void writeShortLE(byte[] buf, int pos, short value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /**
     * Internal class to hold session state
     */
    static class SessionState {
        private final AtomicReference<String> accumulatedText = new AtomicReference<>("");
        private final AtomicBoolean processing = new AtomicBoolean(false);
        /** AI 正在播放 TTS 音频，期间丢弃麦克风回声 */
        private final AtomicBoolean aiSpeaking = new AtomicBoolean(false);
        /** AI 音频播放结束后，额外等待这段时间再接受用户音频（ms），防止回声尾音 */
        private final AtomicLong aiSpeakEndAt = new AtomicLong(0);
        /** 多段 STT completed 拼接，防抖后再送 LLM */
        private final AtomicReference<String> mergeBuffer = new AtomicReference<>("");
        /** mergeBuffer 开始计时点，用于”最长等待补充”判定 */
        private final AtomicLong mergeStartedAt = new AtomicLong(0);
        /** 最近一次 STT 活动时间（partial/final） */
        private final AtomicLong lastSttActivityAt = new AtomicLong(System.currentTimeMillis());
        /** 当前正在执行 LLM+TTS 管线的虚拟线程，断连时可中断 */
        private volatile Thread processingThread = null;

        void appendFinalSttSegment(String segment) {
            String s = segment == null ? "" : segment.trim();
            if (s.isEmpty()) {
                return;
            }
            mergeBuffer.updateAndGet(prev -> {
                if (prev == null || prev.isEmpty()) {
                    mergeStartedAt.set(System.currentTimeMillis());
                    return s;
                }
                return joinSegments(prev, s);
            });
            markSttActivity();
        }

        private static String joinSegments(String previous, String next) {
            String trimmedPrevious = previous.trim();
            String trimmedNext = next.trim();
            if (trimmedNext.equals(trimmedPrevious) || trimmedNext.startsWith(trimmedPrevious)) {
                return trimmedNext;
            }
            if (trimmedPrevious.endsWith(trimmedNext)) {
                return trimmedPrevious;
            }
            if (trimmedPrevious.endsWith("。") || trimmedPrevious.endsWith("！")
                    || trimmedPrevious.endsWith("？") || trimmedPrevious.endsWith(".")
                    || trimmedPrevious.endsWith("!") || trimmedPrevious.endsWith("?")) {
                return trimmedPrevious + " " + trimmedNext;
            }
            return trimmedPrevious + "，" + trimmedNext;
        }

        String getMergeBufferPreview() {
            String s = mergeBuffer.get();
            return s == null ? "" : s;
        }

        void setMergeBufferDirectly(String text) {
            String s = text == null ? "" : text.trim();
            if (s.isEmpty()) {
                return;
            }
            mergeBuffer.set(s);
            if (mergeStartedAt.get() == 0) {
                mergeStartedAt.set(System.currentTimeMillis());
            }
        }

        String getMergeBufferPreviewWithPartial(String partial) {
            String current = partial == null ? "" : partial.trim();
            if (current.isEmpty()) {
                return getMergeBufferPreview();
            }

            String confirmed = getMergeBufferPreview();
            if (confirmed.isBlank()) {
                return current;
            }
            return joinSegments(confirmed, current);
        }

        String takeMergeBufferAndClear() {
            mergeStartedAt.set(0);
            return mergeBuffer.getAndSet("");
        }

        void markSttActivity() {
            lastSttActivityAt.set(System.currentTimeMillis());
        }

        long getMergeStartedAt() {
            long value = mergeStartedAt.get();
            return value > 0 ? value : System.currentTimeMillis();
        }

        long getLastSttActivityAt() {
            return lastSttActivityAt.get();
        }

        String getAccumulatedText() {
            return accumulatedText.get();
        }

        void setAccumulatedText(String text) {
            accumulatedText.set(text);
        }

        AtomicBoolean isProcessing() {
            return processing;
        }

        void setProcessingThread(Thread t) {
            this.processingThread = t;
        }

        public Thread getProcessingThread() {
            return processingThread;
        }

        boolean isAiSpeakingOrCooldown() {
            if (aiSpeaking.get()) {
                return true;
            }
            // AI 播放结束后的冷却期（默认 800ms），防止扬声器尾音被录入
            return System.currentTimeMillis() < aiSpeakEndAt.get();
        }

        void setAiSpeaking(boolean speaking) {
            aiSpeaking.set(speaking);
        }

        void markAiSpeakEnd(long cooldownMs) {
            aiSpeakEndAt.set(System.currentTimeMillis() + cooldownMs);
        }
    }
}
