package interview.guide.modules.voiceinterview.handler;

import interview.guide.modules.voiceinterview.service.QwenTtsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 管理流式 TTS 的并发合成和按句子顺序下发。
 */
@Slf4j
final class VoiceWebSocketTtsChunkEmitter {

    private final String sessionId;
    private final WebSocketSession session;
    private final Semaphore ttsSemaphore;
    private final long ttsTimeoutSec;
    private final QwenTtsService ttsService;
    private final VoiceWebSocketMessageService messageService;
    private final ExecutorService pipelineExecutor;
    private final Function<byte[], byte[]> pcmToWav;
    private final Map<Integer, CompletableFuture<byte[]>> futures = new ConcurrentHashMap<>();
    private final AtomicInteger nextIndex = new AtomicInteger();
    private final AtomicInteger emittedChunks = new AtomicInteger();
    private final Object lock = new Object();
    private final CompletableFuture<Integer> completion;
    private volatile int totalChunks = -1;

    VoiceWebSocketTtsChunkEmitter(
        String sessionId,
        WebSocketSession session,
        Semaphore ttsSemaphore,
        long ttsTimeoutSec,
        QwenTtsService ttsService,
        VoiceWebSocketMessageService messageService,
        ExecutorService pipelineExecutor,
        Function<byte[], byte[]> pcmToWav) {
        this.sessionId = sessionId;
        this.session = session;
        this.ttsSemaphore = ttsSemaphore;
        this.ttsTimeoutSec = ttsTimeoutSec;
        this.ttsService = ttsService;
        this.messageService = messageService;
        this.pipelineExecutor = pipelineExecutor;
        this.pcmToWav = pcmToWav;
        this.completion = CompletableFuture.supplyAsync(this::drainChunks, pipelineExecutor);
    }

    void submit(String sentence) {
        int index = nextIndex.getAndIncrement();
        ttsSemaphore.acquireUninterruptibly();
        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
            try {
                return ttsService.synthesize(sentence);
            } finally {
                ttsSemaphore.release();
            }
        }, pipelineExecutor);

        futures.put(index, future);
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    void finish() {
        synchronized (lock) {
            totalChunks = nextIndex.get();
            lock.notifyAll();
        }
    }

    int awaitCompletion() {
        long timeoutSec = Math.max(ttsTimeoutSec + 2,
            (ttsTimeoutSec + 1) * Math.max(1, nextIndex.get()));
        try {
            return completion.get(timeoutSec, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[Session: {}] Streaming TTS chunk emitter did not finish cleanly: {}",
                sessionId, e.getMessage());
            completion.cancel(true);
            int emitted = emittedChunks.get();
            if (emitted > 0) {
                messageService.sendAudioComplete(session);
            }
            return emitted;
        }
    }

    private int drainChunks() {
        int index = 0;
        try {
            while (true) {
                CompletableFuture<byte[]> future = waitForFuture(index);
                if (future == null) {
                    int emitted = emittedChunks.get();
                    if (emitted > 0) {
                        messageService.sendAudioComplete(session);
                    }
                    return emitted;
                }

                try {
                    byte[] pcm = future.get(ttsTimeoutSec, TimeUnit.SECONDS);
                    if (pcm != null && pcm.length > 0 && session.isOpen()) {
                        messageService.sendAudioChunk(session, pcmToWav.apply(pcm), index, false);
                        emittedChunks.incrementAndGet();
                    }
                } catch (Exception e) {
                    future.cancel(true);
                    log.warn("[Session: {}] Streaming TTS chunk {} failed: {}", sessionId, index, e);
                } finally {
                    futures.remove(index);
                    index++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Session: {}] Streaming TTS chunk emitter interrupted", sessionId);
            int emitted = emittedChunks.get();
            if (emitted > 0) {
                messageService.sendAudioComplete(session);
            }
            return emitted;
        }
    }

    private CompletableFuture<byte[]> waitForFuture(int index) throws InterruptedException {
        synchronized (lock) {
            while (!futures.containsKey(index)) {
                if (totalChunks >= 0 && index >= totalChunks) {
                    return null;
                }
                lock.wait(100);
            }
            return futures.get(index);
        }
    }
}
