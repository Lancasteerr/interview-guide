package interview.guide.modules.knowledgebase.listener;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("向量化生产者消息瘦身测试")
class VectorizeStreamProducerTest {

  @Mock
  private RedisService redisService;
  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;

  private VectorizeStreamProducer producer;

  @BeforeEach
  void setUp() {
    producer = new VectorizeStreamProducer(redisService, knowledgeBaseRepository);
  }

  @Test
  @DisplayName("Stream 消息只包含 kbId 与 retryCount，不携带正文")
  void shouldSendIdOnlyMessage() {
    producer.sendVectorizeTask(42L);

    ArgumentCaptor<Map<String, String>> captor = captureStreamAdd();

    assertThat(captor.getValue())
        .containsEntry("kbId", "42")
        .containsEntry("retryCount", "0")
        .hasSize(2);
  }

  @Test
  @DisplayName("消息大小为常量级，与文档正文长度无关")
  void shouldKeepMessageSizeConstant() {
    producer.sendVectorizeTask(99L);

    ArgumentCaptor<Map<String, String>> captor = captureStreamAdd();
    int totalValueLength = captor.getValue().values().stream()
        .mapToInt(String::length).sum();
    assertThat(totalValueLength).isLessThan(32);
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<Map<String, String>> captureStreamAdd() {
    ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
    verify(redisService).streamAdd(anyString(), captor.capture(), anyInt());
    return captor;
  }
}
