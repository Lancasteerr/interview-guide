package interview.guide.modules.voiceinterview.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import interview.guide.common.log.ErrorLogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 负责解析 Qwen ASR 服务端事件并分发最终文本、局部字幕和错误。
 */
final class QwenAsrEventService {

  private static final Logger log = LoggerFactory.getLogger(QwenAsrService.class);

  void handleServerEvent(
      String sessionId,
      JsonObject message,
      Consumer<String> onFinal,
      Consumer<String> onPartial,
      Consumer<Throwable> onError) {
    try {
      String eventType = message.get("type").getAsString();
      log.trace("[Session: {}] Received event: {}", sessionId, eventType);
      switch (eventType) {
        case "session.created" -> log.debug("[Session: {}] Session created on server", sessionId);
        case "session.updated" -> log.debug("[Session: {}] Session configuration updated", sessionId);
        case "conversation.item.input_audio_transcription.completed" -> {
          String transcript = message.get("transcript").getAsString();
          String language = message.has("language")
              ? message.get("language").getAsString() : "unknown";
          String emotion = message.has("emotion")
              ? message.get("emotion").getAsString() : "neutral";
          log.debug("[Session: {}] Transcription completed - language: {}, emotion: {}, textLength: {}",
              sessionId, language, emotion, transcript.length());
          onFinal.accept(transcript);
        }
        case "conversation.item.input_audio_transcription.text",
             "conversation.item.input_audio_transcription.delta" ->
            dispatchPartialTranscript(sessionId, message, onPartial);
        case "error" -> {
          JsonObject errorObj = message.getAsJsonObject("error");
          String errorType = errorObj.has("type") ? errorObj.get("type").getAsString() : "unknown";
          String errorCode = errorObj.has("code") ? errorObj.get("code").getAsString() : "unknown";
          String errorMessage = errorObj.has("message")
              ? errorObj.get("message").getAsString() : "Unknown error";
          String fullErrorMessage = String.format(
              "ASR Error [%s/%s]: %s", errorType, errorCode,
              ErrorLogSanitizer.summarize(errorMessage));
          log.error("[Session: {}] {}", sessionId, fullErrorMessage);
          onError.accept(new IllegalStateException(fullErrorMessage));
        }
        case "session.finished" -> log.debug("[Session: {}] Session finished on server", sessionId);
        case "conversation.item.input_audio_transcription.failed" ->
            log.error("[Session: {}] ASR transcription failed (single utterance)", sessionId);
        default -> {
          if (eventType != null && eventType.contains("transcription")) {
            log.debug("[Session: {}] Unhandled transcription-related event: type={}", sessionId, eventType);
          } else {
            log.trace("[Session: {}] Unhandled event type: {}", sessionId, eventType);
          }
        }
      }
    } catch (Exception e) {
      log.error("[Session: {}] Error processing server event: {}",
          sessionId, ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
      onError.accept(e);
    }
  }

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

  static String extractTranscriptPayload(JsonObject message) {
    if (message.has("transcript") && !message.get("transcript").isJsonNull()) {
      JsonElement element = message.get("transcript");
      if (element.isJsonPrimitive()) {
        return element.getAsString();
      }
    }
    if (message.has("text") || message.has("stash")) {
      String prefix = message.has("text") && !message.get("text").isJsonNull()
          && message.get("text").isJsonPrimitive() ? message.get("text").getAsString() : "";
      String suffix = message.has("stash") && !message.get("stash").isJsonNull()
          && message.get("stash").isJsonPrimitive() ? message.get("stash").getAsString() : "";
      String combined = prefix + suffix;
      if (!combined.isBlank()) {
        return combined;
      }
    }
    if (message.has("delta")) {
      JsonElement delta = message.get("delta");
      if (delta.isJsonPrimitive()) {
        return delta.getAsString();
      }
      if (delta.isJsonObject()) {
        JsonObject object = delta.getAsJsonObject();
        if (object.has("text") && !object.get("text").isJsonNull()) {
          return object.get("text").getAsString();
        }
        if (object.has("transcript") && !object.get("transcript").isJsonNull()) {
          return object.get("transcript").getAsString();
        }
      }
    }
    if (message.has("item") && message.get("item").isJsonObject()) {
      JsonObject item = message.getAsJsonObject("item");
      if (item.has("transcript") && !item.get("transcript").isJsonNull()) {
        return item.get("transcript").getAsString();
      }
    }
    return null;
  }
}
