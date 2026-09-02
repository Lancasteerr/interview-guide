package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseQueryServiceTest {

  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private KnowledgeBaseVectorService vectorService;
  @Mock
  private KnowledgeBaseListService listService;
  @Mock
  private KnowledgeBaseCountService countService;
  @Mock
  private ResourceLoader resourceLoader;
  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private ChatClient plainChatClient;

  private KnowledgeBaseQueryService service;

  @BeforeEach
  void setUp() throws Exception {
    when(resourceLoader.getResource(anyString()))
        .thenAnswer(invocation -> new ByteArrayResource("模板".getBytes(StandardCharsets.UTF_8)));
  }

  private KnowledgeBaseQueryService buildService(boolean rewriteEnabled) throws Exception {
    KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
    properties.getRewrite().setEnabled(rewriteEnabled);
    return new KnowledgeBaseQueryService(
        llmProviderRegistry,
        vectorService,
        listService,
        countService,
        properties,
        resourceLoader
    );
  }

  private void mockPlainClient() {
    when(llmProviderRegistry.getPlainChatClient()).thenReturn(plainChatClient);
  }

  private void stubDocuments() {
    when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
        .thenReturn(List.of(new Document("相关片段内容")));
  }

  @Nested
  @DisplayName("ChatClient 选择")
  class ChatClientSelection {

    @Test
    @DisplayName("同步问答使用 Plain ChatClient 而非默认 ChatClient")
    void shouldUsePlainChatClientForSyncAnswer() throws Exception {
      service = buildService(false);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      String answer = service.answerQuestion(List.of(1L), "什么是 Java 内存模型");

      assertThat(answer).isEqualTo("同步回答");
      verify(llmProviderRegistry, atLeastOnce()).getPlainChatClient();
      verify(llmProviderRegistry, never()).getDefaultChatClient();
    }

    @Test
    @DisplayName("流式问答使用 Plain ChatClient 而非默认 ChatClient")
    void shouldUsePlainChatClientForStreamAnswer() throws Exception {
      service = buildService(false);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("流式回答"));

      List<String> chunks =
          service.answerQuestionStream(List.of(1L), "什么是 Java 内存模型").collectList().block();

      assertThat(chunks).containsExactly("流式回答");
      verify(llmProviderRegistry, atLeastOnce()).getPlainChatClient();
      verify(llmProviderRegistry, never()).getDefaultChatClient();
    }
  }

  @Nested
  @DisplayName("Query 改写")
  class QueryRewrite {

    @Test
    @DisplayName("改写开启时使用 Plain ChatClient 完成改写并检索")
    void shouldRewriteWithPlainChatClientWhenEnabled() throws Exception {
      service = buildService(true);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("改写后的问题");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      String answer = service.answerQuestion(List.of(1L), "jmm 是什么");

      assertThat(answer).isEqualTo("同步回答");
      verify(llmProviderRegistry, atLeastOnce()).getPlainChatClient();
      verify(llmProviderRegistry, never()).getDefaultChatClient();
    }

    @Test
    @DisplayName("改写关闭时不发起改写调用")
    void shouldSkipRewriteWhenDisabled() throws Exception {
      service = buildService(false);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      String answer = service.answerQuestion(List.of(1L), "什么是 Java 内存模型");

      assertThat(answer).isEqualTo("同步回答");
      verify(plainChatClient.prompt().user(anyString()), never()).call();
      verify(llmProviderRegistry, never()).getDefaultChatClient();
    }

    @Test
    @DisplayName("改写失败时回退原问题并正常回答")
    void shouldFallbackToOriginalQuestionWhenRewriteFails() throws Exception {
      service = buildService(true);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenThrow(new IllegalStateException("改写服务不可用"));
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      String answer = service.answerQuestion(List.of(1L), "什么是 Java 内存模型");

      assertThat(answer).isEqualTo("同步回答");
      verify(llmProviderRegistry, never()).getDefaultChatClient();
    }
  }
}
