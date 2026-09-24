package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.google.gson.JsonObject;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Qwen TTS Realtime Service (WebSocket-based)
 *
 * Provides real-time text-to-speech synthesis using Alibaba Cloud DashScope's
 * qwen-tts-realtime model via WebSocket API.
 *
 * Key Features:
 * - WebSocket-based real-time TTS synthesis
 * - User-commit mode for manual control
 * - Synchronous synthesis API with 30-second timeout protection
 * - Automatic audio chunk collection via response.audio.delta events
 * - Support for Chinese language with configurable voice, speech rate, and volume
 *
 * Configuration:
 * - Model: qwen-tts-realtime
 * - Voice: Configurable (Cherry, Serena, Ethan, etc.)
 * - Audio format: PCM, 24kHz sample rate
 * - Mode: commit (user-controlled)
 *
 * @see QwenTtsRealtime
 * @see QwenTtsRealtimeCallback
 */
@Slf4j
@Service
public class QwenTtsService {

    private final QwenTtsConfiguration configuration;
    private final QwenTtsConnectionService connectionService;
    private final QwenTtsEventService eventService = new QwenTtsEventService();

    // 保留旧字段作为反射/诊断兼容视图，合成流程统一读取 configuration。
    @Deprecated private String model;
    @Deprecated private String apiKey;
    @Deprecated private String voice;
    @Deprecated private String format;
    @Deprecated private Integer sampleRate;
    @Deprecated private String mode;
    @Deprecated private String languageType;
    @Deprecated private Float speechRate;
    @Deprecated private Integer volume;
    @Deprecated private int connectTimeoutSeconds;

    public QwenTtsService(VoiceInterviewProperties voiceInterviewProperties) {
        this.configuration = new QwenTtsConfiguration(voiceInterviewProperties);
        this.connectionService = new QwenTtsConnectionService(configuration);
        syncLegacyConfigurationView();
    }

    public void reload(VoiceInterviewProperties voiceInterviewProperties) {
        configuration.reload(voiceInterviewProperties);
        syncLegacyConfigurationView();
        log.info("QwenTtsService reloaded: model={}, voice={}, connectTimeoutSeconds={}",
                configuration.model(), configuration.voice(), configuration.connectTimeoutSeconds());
    }

    private void syncLegacyConfigurationView() {
        this.model = configuration.model();
        this.apiKey = configuration.apiKey();
        this.voice = configuration.voice();
        this.format = configuration.format();
        this.sampleRate = configuration.sampleRate();
        this.mode = configuration.mode();
        this.languageType = configuration.languageType();
        this.speechRate = configuration.speechRate();
        this.volume = configuration.volume();
        this.connectTimeoutSeconds = configuration.connectTimeoutSeconds();
    }

    /**
     * Initialize the TTS service.
     * This method is automatically called by Spring after the service is constructed
     * and all configuration values have been loaded from VoiceInterviewProperties.
     *
     * @throws IllegalStateException if apiKey is not configured
     */
    @PostConstruct
    public void init() {
        if (!configuration.hasApiKey()) {
            throw new IllegalStateException("API key must be configured before initializing QwenTtsService");
        }
        log.info("QwenTtsService initialized with model: {}, voice: {}, sampleRate: {}Hz",
                 configuration.model(), configuration.voice(), configuration.sampleRate());
    }

    /**
     * Synthesize text to speech audio.
     *
     * This method synchronously converts text to PCM audio data using the DashScope
     * WebSocket-based TTS API. It establishes a WebSocket connection, sends the text for
     * synthesis, collects audio chunks, and returns the complete audio data.
     *
     * The method uses CountDownLatch to wait for synthesis completion with a 30-second
     * timeout to prevent indefinite blocking.
     *
     * @param text Text to synthesize (null, empty, or whitespace-only text returns empty array)
     * @return PCM audio data at configured sample rate, or empty array if synthesis fails
     */
    public byte[] synthesize(String text) {
        // Handle null, empty, or whitespace-only text
        if (text == null || text.trim().isEmpty()) {
            log.debug("Empty or null text provided, returning empty audio array");
            return new byte[0];
        }

        log.debug("Starting TTS synthesis for text: {} characters", text.length());

        // Latch for synchronous waiting
        CountDownLatch synthesisLatch = new CountDownLatch(1);

        // Container for collected audio data
        ByteArrayContainer audioContainer = new ByteArrayContainer();

        // Error container
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Response ID container for tracking
        AtomicReference<String> responseIdRef = new AtomicReference<>();

        try {
            // Build QwenTtsRealtimeParam with connection settings
            QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
                    .model(configuration.model())
                    .apikey(configuration.apiKey())
                    .build();

            // Create callback handler for WebSocket events
            QwenTtsRealtimeCallback callback = new QwenTtsRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("TTS WebSocket connection established");
                }

                @Override
                public void onEvent(JsonObject message) {
                    handleServerEvent(message, audioContainer, synthesisLatch, errorRef, responseIdRef);
                }

                @Override
                public void onClose(int code, String reason) {
                    log.debug("TTS WebSocket closed - code: {}, reason: {}", code, reason);
                    synthesisLatch.countDown();
                }
            };

            QwenTtsRealtime qwenTtsRealtime = connectionService.open(param, callback);

            try {
                // Connect to server (blocking)
                // Configure session with TTS parameters
                QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
                        .voice(configuration.voice())
                        .responseFormat(getAudioFormat())
                        .mode(configuration.mode())  // "commit" mode
                        .languageType(configuration.languageType())
                        .speechRate(configuration.speechRate())
                        .volume(configuration.volume())
                        .build();

                // Update session with configuration
                qwenTtsRealtime.updateSession(config);

                log.info("[TTS] Session configured with voice: {}, triggering synthesis for text (length: {})",
                         configuration.voice(), text.length());

                // Send text for synthesis using commit mode
                qwenTtsRealtime.appendText(text);
                qwenTtsRealtime.commit();

                log.info("[TTS] Text sent to TTS service, waiting for audio response...");

                // Wait for synthesis completion with timeout
                boolean completed = synthesisLatch.await(30, TimeUnit.SECONDS);

                if (!completed) {
                    log.error("TTS synthesis timeout after 30 seconds");
                    return new byte[0];
                }

                // Check if error occurred
                Throwable error = errorRef.get();
                if (error != null) {
                    log.error("TTS synthesis failed", error);
                    return new byte[0];
                }

                // Return collected audio data
                byte[] audioData = audioContainer.toByteArray();
                log.info("[TTS] Synthesis completed successfully - {} bytes of audio data, responseId: {}",
                         audioData.length, responseIdRef.get());

                return audioData;

            } finally {
                // Ensure connection is closed
                try {
                    qwenTtsRealtime.close();
                } catch (Exception e) {
                    log.error("Error closing TTS connection", e);
                }
            }

        } catch (InterruptedException e) {
            log.error("TTS synthesis interrupted", e);
            Thread.currentThread().interrupt();
            return new byte[0];
        } catch (Exception e) {
            log.error("Failed to synthesize text", e);
            return new byte[0];
        }
    }

    /**
     * Get audio format for Qwen TTS Realtime.
     * Currently supports 24kHz PCM format.
     *
     * @return QwenTtsRealtimeAudioFormat enum value
     */
    private QwenTtsRealtimeAudioFormat getAudioFormat() {
        // Qwen TTS Realtime uses 24kHz by default
        return QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;
    }

    /**
     * Destroy the service and cleanup resources.
     *
     * This method is called automatically when the Spring container shuts down.
     * Currently, no persistent resources need cleanup as each synthesis creates
     * its own temporary connection.
     */
    @PreDestroy
    public void destroy() {
        log.info("QwenTtsService destroyed successfully");
    }

    /**
     * Handle server events from the DashScope TTS service.
     *
     * This method processes various event types:
     * - session.created: Session successfully created
     * - session.updated: Session configuration updated
     * - response.audio.delta: Audio chunk received
     * - response.done: Response completed
     * - error: Error occurred
     *
     * @param message JSON event message from server
     * @param audioContainer Container for collecting audio chunks
     * @param synthesisLatch Latch to signal completion
     * @param errorRef Container for error tracking
     * @param responseIdRef Container for response ID tracking
     */
    private void handleServerEvent(JsonObject message, ByteArrayContainer audioContainer,
                                    CountDownLatch synthesisLatch, AtomicReference<Throwable> errorRef,
                                    AtomicReference<String> responseIdRef) {
        eventService.handleServerEvent(
            message, audioContainer, synthesisLatch, errorRef, responseIdRef);
    }

    /**
     * Internal class for efficiently collecting audio chunks.
     * Uses ByteArrayOutputStream for amortized O(1) append performance
     * instead of O(n²) copying with manual array growth.
     */
    static class ByteArrayContainer {
        private final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        public synchronized void append(byte[] chunk) {
            baos.write(chunk, 0, chunk.length);
        }

        public synchronized byte[] toByteArray() {
            return baos.toByteArray();
        }
    }

    // Setter methods for configuration (used by Spring @Value injection or tests)

    public void setModel(String model) {
        configuration.setModel(model);
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        configuration.setApiKey(apiKey);
        this.apiKey = apiKey;
    }

    public void setVoice(String voice) {
        configuration.setVoice(voice);
        this.voice = voice;
    }

    public void setFormat(String format) {
        configuration.setFormat(format);
        this.format = format;
    }

    public void setSampleRate(Integer sampleRate) {
        configuration.setSampleRate(sampleRate);
        this.sampleRate = sampleRate;
    }

    public void setMode(String mode) {
        configuration.setMode(mode);
        this.mode = mode;
    }

    public void setLanguageType(String languageType) {
        configuration.setLanguageType(languageType);
        this.languageType = languageType;
    }

    public void setSpeechRate(Float speechRate) {
        configuration.setSpeechRate(speechRate);
        this.speechRate = speechRate;
    }

    public void setVolume(Integer volume) {
        configuration.setVolume(volume);
        this.volume = volume;
    }
}
