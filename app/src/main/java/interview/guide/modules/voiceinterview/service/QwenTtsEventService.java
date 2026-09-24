package interview.guide.modules.voiceinterview.service;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 负责解析 Qwen TTS 事件、聚合音频分片并通知合成完成或失败。
 */
final class QwenTtsEventService {

  private static final Logger log = LoggerFactory.getLogger(QwenTtsService.class);

  void handleServerEvent(
      JsonObject message,
      QwenTtsService.ByteArrayContainer audioContainer,
      CountDownLatch synthesisLatch,
      AtomicReference<Throwable> errorRef,
      AtomicReference<String> responseIdRef) {
    try {
      String eventType = message.get("type").getAsString();
      if (log.isTraceEnabled()) {
        log.trace("Received TTS event: {}, full message: {}", eventType, message);
      } else {
        log.debug("Received TTS event: {}", eventType);
      }

      switch (eventType) {
        case "session.created" -> {
          String sessionId = message.has("session") && message.get("session").isJsonObject()
              ? message.get("session").getAsJsonObject().get("id").getAsString() : "unknown";
          log.debug("TTS session created: {}", sessionId);
        }
        case "session.updated" -> log.debug("TTS session configuration updated");
        case "response.audio.delta" -> {
          if (message.has("delta")) {
            String audioBase64 = message.get("delta").getAsString();
            if (audioBase64 != null && !audioBase64.isEmpty()) {
              byte[] audioChunk = Base64.getDecoder().decode(audioBase64);
              audioContainer.append(audioChunk);
              log.trace("Received audio chunk - {} bytes", audioChunk.length);
            }
          }
        }
        case "response.done" -> {
          log.debug("TTS response completed - responseId: {}", responseIdRef.get());
          synthesisLatch.countDown();
        }
        case "error" -> handleError(message, synthesisLatch, errorRef);
        default -> log.trace("Unhandled TTS event type: {}", eventType);
      }
    } catch (Exception e) {
      log.error("Error processing TTS server event", e);
      errorRef.set(e);
      synthesisLatch.countDown();
    }
  }

  private void handleError(
      JsonObject message,
      CountDownLatch synthesisLatch,
      AtomicReference<Throwable> errorRef) {
    if (!message.has("error")) {
      return;
    }
    var errorElement = message.get("error");
    String errorType = "unknown";
    String errorCode = "unknown";
    String errorMessage = "Unknown error";
    if (errorElement.isJsonObject()) {
      JsonObject errorObject = errorElement.getAsJsonObject();
      errorType = errorObject.has("type") ? errorObject.get("type").getAsString() : "unknown";
      errorCode = errorObject.has("code") ? errorObject.get("code").getAsString() : "unknown";
      errorMessage = errorObject.has("message")
          ? errorObject.get("message").getAsString() : "Unknown error";
    } else {
      errorMessage = errorElement.toString();
    }
    String fullErrorMessage = String.format(
        "TTS Error [%s/%s]: %s", errorType, errorCode, errorMessage);
    log.error("{}", fullErrorMessage);
    errorRef.set(new IllegalStateException(fullErrorMessage));
    synthesisLatch.countDown();
  }
}
