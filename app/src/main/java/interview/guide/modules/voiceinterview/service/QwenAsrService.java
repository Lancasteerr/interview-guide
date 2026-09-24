package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.google.gson.JsonObject;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.function.Consumer;

/**
 * Qwen3 Realtime ASR Service
 *
 * Provides real-time speech recognition using Alibaba Cloud DashScope's qwen3-asr-flash-realtime model.
 * This service manages WebSocket connections for multiple concurrent sessions and handles
 * audio transcription with server-side Voice Activity Detection (VAD).
 *
 * Key Features:
 * - Multi-session management with thread-safe concurrent map
 * - Server-side VAD with 400ms silence duration for automatic sentence detection
 * - Callback-based result handling for real-time transcription updates
 * - Automatic resource cleanup on session termination
 *
 * Configuration:
 * - Model: qwen3-asr-flash-realtime
 * - Audio format: PCM, 16kHz sample rate
 * - Language: Chinese (zh)
 * - VAD: Enabled with server_vad type
 *
 * @see OmniRealtimeConversation
 * @see OmniRealtimeCallback
 */
@Slf4j
@Service
public class QwenAsrService {

    private final QwenAsrConfiguration configuration;
    private final QwenAsrEventService eventService = new QwenAsrEventService();

    // 保留旧字段作为反射/诊断兼容视图，连接流程统一读取 configuration。
    @Deprecated private String url;
    @Deprecated private String model;
    @Deprecated private String apiKey;
    @Deprecated private String language;
    @Deprecated private String format;
    @Deprecated private Integer sampleRate;
    @Deprecated private Boolean enableTurnDetection;
    @Deprecated private String turnDetectionType;
    @Deprecated private Float turnDetectionThreshold;
    @Deprecated private Integer turnDetectionSilenceDurationMs;

    public QwenAsrService(VoiceInterviewProperties voiceInterviewProperties) {
        this.configuration = new QwenAsrConfiguration(voiceInterviewProperties.getQwen().getAsr());
        syncLegacyConfigurationView();
        this.connectionService = new QwenAsrConnectionService(configuration, sessionManager, eventService);
    }

    public void reload(VoiceInterviewProperties voiceInterviewProperties) {
        configuration.reload(voiceInterviewProperties.getQwen().getAsr());
        syncLegacyConfigurationView();
        log.info("QwenAsrService reloaded: model={}, url={}", configuration.model(), configuration.url());
    }

    private void syncLegacyConfigurationView() {
        this.url = configuration.url();
        this.model = configuration.model();
        this.apiKey = configuration.apiKey();
        this.language = configuration.language();
        this.format = configuration.format();
        this.sampleRate = configuration.sampleRate();
        this.enableTurnDetection = configuration.enableTurnDetection();
        this.turnDetectionType = configuration.turnDetectionType();
        this.turnDetectionThreshold = configuration.turnDetectionThreshold();
        this.turnDetectionSilenceDurationMs = configuration.turnDetectionSilenceDurationMs();
    }

    private final QwenAsrSessionManager sessionManager = new QwenAsrSessionManager();
    private final QwenAsrConnectionService connectionService;

    /**
     * Initialize the ASR service.
     * This method is automatically called by Spring after the service is constructed
     * and all configuration values have been loaded from VoiceInterviewProperties.
     *
     * @throws IllegalStateException if apiKey is not configured
     */
    @PostConstruct
    public void init() {
        if (!configuration.hasApiKey()) {
            throw new IllegalStateException("API key must be configured before initializing QwenAsrService");
        }
        log.info("QwenAsrService initialized with model: {}, url: {}", configuration.model(), configuration.url());
    }

    /**
     * Start a new transcription session.
     */
    public void startTranscription(String sessionId, Consumer<String> onFinal, Consumer<Throwable> onError) {
        startTranscription(sessionId, onFinal, null, onError);
    }

    public void startTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        startTranscription(sessionId, onFinal, onPartial, null, onError);
    }

    public void startTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
        connectionService.start(sessionId, onFinal, onPartial, onReady, onError);
    }

    /**
     * 停止旧连接并重新建立。
     */
    public void restartTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        restartTranscription(sessionId, onFinal, onPartial, null, onError);
    }

    public void restartTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
        connectionService.restart(sessionId, onFinal, onPartial, onReady, onError);
    }

    public void sendAudio(String sessionId, byte[] audioData) {
        connectionService.sendAudio(sessionId, audioData);
    }

    public void stopTranscription(String sessionId) {
        connectionService.stop(sessionId);
    }

    public boolean hasActiveSession(String sessionId) {
        return sessionManager.contains(sessionId);
    }

    public boolean isReady(String sessionId) {
        QwenAsrSessionManager.Session session = sessionManager.get(sessionId);
        return session != null && session.isReady();
    }

    /**
     * Destroy the service and cleanup all active sessions.
     */
    @PreDestroy
    public void destroy() {
        connectionService.destroy();
    }

    /**
     * Handle server events from the DashScope ASR service.
     *
     * This method processes various event types:
     * - session.created: Session successfully created
     * - session.updated: Session configuration updated
     * - conversation.item.input_audio_transcription.completed: Final transcription result
     * - conversation.item.input_audio_transcription.text / .delta: Partial transcription (live subtitles)
     * - error: Error occurred
     *
     * @param sessionId Session identifier
     * @param message JSON event message from server
     * @param onFinal Callback for finalized segment text
     * @param onPartial Callback for streaming partial text (optional)
     * @param onError Callback for errors
     */
    private void handleServerEvent(
            String sessionId,
            JsonObject message,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        eventService.handleServerEvent(sessionId, message, onFinal, onPartial, onError);
    }

    /**
     * Forward partial / streaming ASR text for real-time UI (VAD alone does not imply visible STT).
     */
    private void dispatchPartialTranscript(
            String sessionId, JsonObject message, Consumer<String> onPartial) {
        if (onPartial == null) {
            log.trace("[Session: {}] Partial transcription received (no consumer)", sessionId);
            return;
        }
        String text = extractTranscriptPayload(message);
        if (text != null && !text.isBlank()) {
            onPartial.accept(text);
        } else {
            log.trace("[Session: {}] Partial ASR event without extractable text", sessionId);
        }
    }

    /**
     * Extract displayable text from ASR JSON events.
     * <p>
     * For {@code conversation.item.input_audio_transcription.text}, the official preview is
     * {@code text} (confirmed prefix) + {@code stash} (draft suffix); either may be empty.
     * </p>
     */
    static String extractTranscriptPayload(JsonObject message) {
        return QwenAsrEventService.extractTranscriptPayload(message);
    }

    // Setter methods for configuration (used by Spring @Value injection or tests)

    public void setUrl(String url) {
        configuration.setUrl(url);
        this.url = url;
    }

    public void setModel(String model) {
        configuration.setModel(model);
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        configuration.setApiKey(apiKey);
        this.apiKey = apiKey;
    }

    public void setLanguage(String language) {
        configuration.setLanguage(language);
        this.language = language;
    }

    public void setFormat(String format) {
        configuration.setFormat(format);
        this.format = format;
    }

    public void setSampleRate(Integer sampleRate) {
        configuration.setSampleRate(sampleRate);
        this.sampleRate = sampleRate;
    }

    public void setEnableTurnDetection(Boolean enableTurnDetection) {
        configuration.setEnableTurnDetection(enableTurnDetection);
        this.enableTurnDetection = enableTurnDetection;
    }

    public void setTurnDetectionType(String turnDetectionType) {
        configuration.setTurnDetectionType(turnDetectionType);
        this.turnDetectionType = turnDetectionType;
    }

    public void setTurnDetectionThreshold(Float turnDetectionThreshold) {
        configuration.setTurnDetectionThreshold(turnDetectionThreshold);
        this.turnDetectionThreshold = turnDetectionThreshold;
    }

    public void setTurnDetectionSilenceDurationMs(Integer turnDetectionSilenceDurationMs) {
        configuration.setTurnDetectionSilenceDurationMs(turnDetectionSilenceDurationMs);
        this.turnDetectionSilenceDurationMs = turnDetectionSilenceDurationMs;
    }
}
