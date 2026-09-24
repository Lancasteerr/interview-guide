package interview.guide.modules.knowledgebase.service.rag;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;

/** 负责 RAG 模型答案的规范化和流式输出探测。 */
final class KnowledgeBaseRagResponseService {

  private static final int STREAM_PROBE_CHARS = 120;
  private final String noResultResponse;

  KnowledgeBaseRagResponseService(String noResultResponse) {
    this.noResultResponse = noResultResponse;
  }

  String normalizeAnswer(String answer) {
    if (answer == null || answer.isBlank()) {
      return noResultResponse;
    }
    String normalized = answer.trim();
    return isNoResultLike(normalized) ? noResultResponse : normalized;
  }

  Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
    return Flux.create(sink -> {
      StringBuilder probeBuffer = new StringBuilder();
      AtomicBoolean passthrough = new AtomicBoolean(false);
      AtomicBoolean completed = new AtomicBoolean(false);
      final Disposable[] disposableRef = new Disposable[1];

      disposableRef[0] = rawFlux.subscribe(
          chunk -> {
            if (completed.get() || sink.isCancelled()) {
              return;
            }
            if (passthrough.get()) {
              sink.next(chunk);
              return;
            }

            probeBuffer.append(chunk);
            String probeText = probeBuffer.toString();
            if (isNoResultLike(probeText)) {
              completed.set(true);
              sink.next(noResultResponse);
              sink.complete();
              if (disposableRef[0] != null) {
                disposableRef[0].dispose();
              }
              return;
            }

            if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
              passthrough.set(true);
              sink.next(probeText);
              probeBuffer.setLength(0);
            }
          },
          sink::error,
          () -> {
            if (completed.get() || sink.isCancelled()) {
              return;
            }
            if (!passthrough.get()) {
              sink.next(normalizeAnswer(probeBuffer.toString()));
            }
            sink.complete();
          });

      sink.onCancel(() -> {
        if (disposableRef[0] != null) {
          disposableRef[0].dispose();
        }
      });
    });
  }

  private boolean isNoResultLike(String text) {
    return text.contains("没有找到相关信息")
        || text.contains("未检索到相关信息")
        || text.contains("信息不足")
        || text.contains("超出知识库范围")
        || text.contains("无法根据提供内容回答");
  }
}
