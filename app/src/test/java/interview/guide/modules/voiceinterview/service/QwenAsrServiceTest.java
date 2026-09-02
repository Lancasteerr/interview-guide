package interview.guide.modules.voiceinterview.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QwenAsrService Unit Tests")
class QwenAsrServiceTest {

    private QwenAsrService asrService;

    @BeforeEach
    void setUp() {
        VoiceInterviewProperties properties = new VoiceInterviewProperties();
        VoiceInterviewProperties.AsrConfig asr = properties.getQwen().getAsr();
        asr.setUrl("wss://dashscope.aliyuncs.com/api-ws/v1/realtime");
        asr.setModel("qwen3-asr-flash-realtime");
        asr.setApiKey("test-api-key");
        asr.setLanguage("zh");
        asr.setFormat("pcm");
        asr.setSampleRate(16000);
        asr.setEnableTurnDetection(true);
        asr.setTurnDetectionType("server_vad");
        asr.setTurnDetectionThreshold(0.0f);
        asr.setTurnDetectionSilenceDurationMs(400);

        asrService = new QwenAsrService(properties);
    }

    @Test
    @DisplayName("Should initialize service successfully")
    void testInit() {
        assertDoesNotThrow(() -> asrService.init());
    }

    @Test
    @DisplayName("Should start transcription and create session")
    void testStartTranscription() throws Exception {
        asrService.init();

        String sessionId = "test-session-1";
        AtomicReference<String> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        asrService.startTranscription(
            sessionId,
            text -> resultRef.set(text),
            error -> errorRef.set(error)
        );

        // Verify session was created
        assertTrue(asrService.hasActiveSession(sessionId));

        // Cleanup
        asrService.stopTranscription(sessionId);
    }

    @Test
    @DisplayName("Should stop transcription and remove session")
    void testStopTranscription() throws Exception {
        asrService.init();

        String sessionId = "test-session-2";
        CountDownLatch latch = new CountDownLatch(1);

        asrService.startTranscription(
            sessionId,
            text -> {},
            error -> latch.countDown()
        );

        assertTrue(asrService.hasActiveSession(sessionId));

        asrService.stopTranscription(sessionId);

        assertFalse(asrService.hasActiveSession(sessionId));
    }

    @Test
    @DisplayName("Should handle multiple concurrent sessions")
    void testMultipleSessions() throws Exception {
        asrService.init();

        String session1 = "session-1";
        String session2 = "session-2";

        asrService.startTranscription(session1, text -> {}, error -> {});
        asrService.startTranscription(session2, text -> {}, error -> {});

        assertTrue(asrService.hasActiveSession(session1));
        assertTrue(asrService.hasActiveSession(session2));

        asrService.stopTranscription(session1);
        asrService.stopTranscription(session2);

        assertFalse(asrService.hasActiveSession(session1));
        assertFalse(asrService.hasActiveSession(session2));
    }

    @Test
    @DisplayName("Should throw exception when sending audio to non-existent session")
    void testSendAudioToNonExistentSession() {
        asrService.init();

        byte[] audioData = new byte[1024];

        assertThrows(IllegalStateException.class, () -> {
            asrService.sendAudio("non-existent-session", audioData);
        });
    }

    @Test
    @DisplayName("extractTranscriptPayload should concatenate text + stash for partial ASR events")
    void extractTranscriptPayload_textAndStash() {
        JsonObject o = JsonParser.parseString(
                "{\"type\":\"conversation.item.input_audio_transcription.text\",\"text\":\"\",\"stash\":\"北京的\"}"
        ).getAsJsonObject();
        assertEquals("北京的", QwenAsrService.extractTranscriptPayload(o));
    }

    @Test
    @DisplayName("extractTranscriptPayload should merge confirmed prefix and draft suffix")
    void extractTranscriptPayload_mixedPrefixSuffix() {
        JsonObject o = JsonParser.parseString(
                "{\"type\":\"conversation.item.input_audio_transcription.text\",\"text\":\"今天天气不错，\",\"stash\":\"阳光\"}"
        ).getAsJsonObject();
        assertEquals("今天天气不错，阳光", QwenAsrService.extractTranscriptPayload(o));
    }

    @Test
    @DisplayName("Should cleanup resources on destroy")
    void testDestroy() throws Exception {
        asrService.init();

        // Create multiple sessions
        asrService.startTranscription("session-1", text -> {}, error -> {});
        asrService.startTranscription("session-2", text -> {}, error -> {});

        // Destroy should cleanup all sessions
        assertDoesNotThrow(() -> asrService.destroy());

        assertFalse(asrService.hasActiveSession("session-1"));
        assertFalse(asrService.hasActiveSession("session-2"));
    }

    @Test
    @DisplayName("reload 应更新所有 ASR 配置字段")
    void testReloadUpdatesAllFields() throws Exception {
        VoiceInterviewProperties newProps = new VoiceInterviewProperties();
        VoiceInterviewProperties.AsrConfig newAsr = newProps.getQwen().getAsr();
        newAsr.setUrl("wss://new-host.example.com/ws");
        newAsr.setModel("new-asr-model");
        newAsr.setApiKey("new-api-key");
        newAsr.setLanguage("en");
        newAsr.setFormat("wav");
        newAsr.setSampleRate(8000);
        newAsr.setEnableTurnDetection(false);
        newAsr.setTurnDetectionType("client_vad");
        newAsr.setTurnDetectionThreshold(0.5f);
        newAsr.setTurnDetectionSilenceDurationMs(200);

        asrService.reload(newProps);

        assertEquals("wss://new-host.example.com/ws", field(asrService, "url"));
        assertEquals("new-asr-model", field(asrService, "model"));
        assertEquals("new-api-key", field(asrService, "apiKey"));
        assertEquals("en", field(asrService, "language"));
        assertEquals("wav", field(asrService, "format"));
        assertEquals(8000, field(asrService, "sampleRate"));
        assertEquals(false, field(asrService, "enableTurnDetection"));
        assertEquals("client_vad", field(asrService, "turnDetectionType"));
        assertEquals(0.5f, field(asrService, "turnDetectionThreshold"));
        assertEquals(200, field(asrService, "turnDetectionSilenceDurationMs"));
    }

    private static Object field(Object obj, String name) throws Exception {
        java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    @Nested
    @DisplayName("ASR 事件日志隐私")
    class EventLogPrivacy {

        private final Logger asrLogger =
                (Logger) LoggerFactory.getLogger(QwenAsrService.class);
        private final ListAppender<ILoggingEvent> appender =
                new ListAppender<>();
        private Level originalLevel;

        @BeforeEach
        void setUpAppender() {
            originalLevel = asrLogger.getLevel();
            asrLogger.setLevel(Level.DEBUG);
            appender.start();
            asrLogger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            asrLogger.setLevel(originalLevel);
            asrLogger.detachAppender(appender);
        }

        private String capturedLogs() {
            StringBuilder sb = new StringBuilder();
            for (ILoggingEvent event : appender.list) {
                sb.append(event.getFormattedMessage()).append('\n');
            }
            return sb.toString();
        }

        private void invokeHandleServerEvent(String sessionId, JsonObject message) throws Exception {
            Method method = QwenAsrService.class.getDeclaredMethod(
                    "handleServerEvent", String.class, JsonObject.class,
                    Consumer.class, Consumer.class,
                    Consumer.class);
            method.setAccessible(true);
            method.invoke(asrService, sessionId, message,
                    (Consumer<String>) text -> { },
                    (Consumer<String>) text -> { },
                    (Consumer<Throwable>) error -> { });
        }

        @Test
        @DisplayName("转写完成事件日志不包含转写原文，只记录长度")
        void shouldNotLogTranscriptText() throws Exception {
            String marker = "转写敏感标记MARKER-ASR-4d8e";
            JsonObject message = JsonParser.parseString(
                    "{\"type\":\"conversation.item.input_audio_transcription.completed\","
                            + "\"transcript\":\"" + marker + "\",\"language\":\"zh\"}").getAsJsonObject();

            invokeHandleServerEvent("sess-log-1", message);

                assertTrue(capturedLogs().contains("textLength: " + marker.length()));
                assertFalse(capturedLogs().contains(marker));
        }

        @Test
        @DisplayName("转写失败事件日志不包含完整 JSON 消息体")
        void shouldNotLogFailedEventPayload() throws Exception {
            String marker = "失败事件敏感标记MARKER-ASR-FAIL-6a1f";
            JsonObject message = JsonParser.parseString(
                    "{\"type\":\"conversation.item.input_audio_transcription.failed\","
                            + "\"transcript\":\"" + marker + "\"}").getAsJsonObject();

            invokeHandleServerEvent("sess-log-2", message);

                assertTrue(capturedLogs().contains("sess-log-2"));
                assertFalse(capturedLogs().contains(marker));
        }

        @Test
        @DisplayName("未处理转写事件日志只记录事件类型")
        void shouldNotLogUnhandledEventPayload() throws Exception {
            String marker = "未处理事件敏感标记MARKER-ASR-UNHANDLED-77aa";
            JsonObject message = JsonParser.parseString(
                    "{\"type\":\"conversation.item.input_audio_transcription.unknown\","
                            + "\"transcript\":\"" + marker + "\"}").getAsJsonObject();

            invokeHandleServerEvent("sess-log-3", message);

                assertTrue(capturedLogs().contains(
                        "conversation.item.input_audio_transcription.unknown"));
                assertFalse(capturedLogs().contains(marker));
        }
    }
}
