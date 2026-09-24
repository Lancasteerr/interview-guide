package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.WebSocket;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Qwen TTS WebSocket 客户端连接生命周期管理。
 */
@Slf4j
final class QwenTtsConnectionService {

  private final QwenTtsConfiguration configuration;

  QwenTtsConnectionService(QwenTtsConfiguration configuration) {
    this.configuration = configuration;
  }

  QwenTtsRealtime open(QwenTtsRealtimeParam param, QwenTtsRealtimeCallback callback) throws Exception {
    AtomicReference<Throwable> connectionFailureRef = new AtomicReference<>();
    CountDownLatch connectionFinishedLatch = new CountDownLatch(1);
    QwenTtsRealtime client = createClient(
        param, callback, connectionFailureRef, connectionFinishedLatch);

    try {
      connectWithTimeout(client, connectionFailureRef, connectionFinishedLatch);
      return client;
    } catch (Exception e) {
      closeQuietly(client);
      throw e;
    }
  }

  private QwenTtsRealtime createClient(
      QwenTtsRealtimeParam param,
      QwenTtsRealtimeCallback callback,
      AtomicReference<Throwable> connectionFailureRef,
      CountDownLatch connectionFinishedLatch) {
    return new QwenTtsRealtime(param, callback) {
      @Override
      public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
        String status = response == null
            ? "no HTTP response"
            : "HTTP " + response.code() + " " + response.message();
        connectionFailureRef.compareAndSet(
            null,
            new IllegalStateException("TTS WebSocket handshake failed: " + status, throwable));
        connectionFinishedLatch.countDown();
      }
    };
  }

  private void connectWithTimeout(
      QwenTtsRealtime client,
      AtomicReference<Throwable> connectionFailureRef,
      CountDownLatch connectionFinishedLatch) throws Exception {
    Thread connectThread = Thread.ofVirtual()
        .name("qwen-tts-connect")
        .start(() -> {
          try {
            client.connect();
          } catch (Throwable throwable) {
            connectionFailureRef.compareAndSet(null, throwable);
          } finally {
            connectionFinishedLatch.countDown();
          }
        });

    try {
      boolean completed = connectionFinishedLatch.await(
          configuration.connectTimeoutSeconds(), TimeUnit.SECONDS);
      if (!completed) {
        throw new TimeoutException(
            "TTS WebSocket connection timed out after "
                + configuration.connectTimeoutSeconds() + " seconds");
      }
    } finally {
      if (connectThread.isAlive()) {
        connectThread.interrupt();
      }
    }

    Throwable failure = connectionFailureRef.get();
    if (failure instanceof Exception exception) {
      throw exception;
    }
    if (failure != null) {
      throw new IllegalStateException("TTS WebSocket connection failed", failure);
    }
  }

  private void closeQuietly(QwenTtsRealtime client) {
    try {
      client.close();
    } catch (Exception e) {
      log.debug("Error closing TTS connection after failed open", e);
    }
  }
}
