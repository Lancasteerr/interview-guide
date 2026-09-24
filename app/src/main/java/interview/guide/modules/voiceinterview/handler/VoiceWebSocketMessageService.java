package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.voiceinterview.dto.WebSocketSubtitleMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

/**
 * 统一负责语音面试 WebSocket 的 JSON 序列化和下行协议消息。
 */
@Slf4j
@Component
final class VoiceWebSocketMessageService {

    private final ObjectMapper objectMapper;

    VoiceWebSocketMessageService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String createWelcomeMessage() {
        return toJson(Map.of(
            "type", "control",
            "action", "welcome",
            "message", "连接成功，准备开始语音面试",
            "timestamp", System.currentTimeMillis()
        ));
    }

    void sendSubtitle(WebSocketSession session, String text, boolean isFinal) {
        WebSocketSubtitleMessage subtitle = WebSocketSubtitleMessage.builder()
            .type("subtitle")
            .text(text)
            .isFinal(isFinal)
            .build();
        sendMessage(session, toJson(subtitle));
    }

    void sendAudio(WebSocketSession session, byte[] audio, String text) {
        if (!session.isOpen()) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(audio);
        log.info("Sending audio to frontend - WAV size: {} bytes, Base64 length: {}",
            audio.length, base64Audio.length());
        sendMessage(session, toJson(Map.of(
            "type", "audio",
            "data", base64Audio,
            "text", text
        )));
    }

    void sendTextMessage(WebSocketSession session, String text) {
        sendTextMessage(session, text, false);
    }

    void sendTextMessage(WebSocketSession session, String text, boolean isFinal) {
        sendMessage(session, toJson(Map.of(
            "type", "text",
            "content", text,
            "final", isFinal
        )));
    }

    void sendError(WebSocketSession session, String error) {
        sendMessage(session, toJson(Map.of("type", "error", "message", error)));
    }

    void sendAsrReady(WebSocketSession session) {
        sendAsrStatus(session, "asr_ready", "语音识别已就绪");
    }

    void sendAsrStatus(WebSocketSession session, String action, String message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        sendMessage(session, toJson(Map.of(
            "type", "control",
            "action", action,
            "message", message,
            "timestamp", System.currentTimeMillis()
        )));
    }

    void sendControl(WebSocketSession session, String action, String message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        sendMessage(session, toJson(Map.of(
            "type", "control",
            "action", action,
            "message", message,
            "timestamp", System.currentTimeMillis()
        )));
    }

    void sendAudioChunk(WebSocketSession session, byte[] wavAudio, int index, boolean isLast) {
        if (!session.isOpen()) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(wavAudio);
        sendMessage(session, toJson(Map.of(
            "type", "audio_chunk",
            "data", base64Audio,
            "index", index,
            "isLast", isLast
        )));
        log.debug("[Session] Sent audio chunk index={}, isLast={}, size={} bytes",
            index, isLast, wavAudio.length);
    }

    void sendAudioComplete(WebSocketSession session) {
        if (session == null || !session.isOpen()) {
            return;
        }
        sendMessage(session, toJson(Map.of(
            "type", "control",
            "action", "audio_complete",
            "message", "面试官语音播放完成",
            "timestamp", System.currentTimeMillis()
        )));
    }

    void sendMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
                log.debug("Message sent to session: length={}", message.length());
            } else {
                log.warn("Session is closed, cannot send message");
            }
        } catch (Exception e) {
            log.error("Error sending message to session", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Error serializing JSON", e);
            return "{}";
        }
    }
}
