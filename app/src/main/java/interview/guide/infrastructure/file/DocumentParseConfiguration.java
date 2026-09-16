package interview.guide.infrastructure.file;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 文档解析专用有界线程池。 */
@Configuration
public class DocumentParseConfiguration {

  @Bean(name = "documentParseExecutor", destroyMethod = "shutdown")
  public ThreadPoolExecutor documentParseExecutor(DocumentParseProperties properties) {
    AtomicInteger sequence = new AtomicInteger();
    return new ThreadPoolExecutor(
        properties.getPoolSize(),
        properties.getPoolSize(),
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(properties.getQueueCapacity()),
        runnable -> {
          Thread thread = new Thread(runnable,
              "document-parse-" + sequence.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }
}
