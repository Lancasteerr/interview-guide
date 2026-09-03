package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
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
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

  @Test
  @DisplayName("带轨迹收集器的流式入口输出完整执行轨迹且不影响答案")
  void shouldEmitExecutionTraceForStream() throws Exception {
    service = buildService(false);
    mockPlainClient();
    stubDocuments();
    when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
        .thenReturn(Flux.just("流式回答"));
    List<interview.guide.modules.knowledgebase.service.RagQueryExecution> traces = new ArrayList<>();

    List<String> chunks = service
        .answerQuestionStream(List.of(1L), "什么是 Java 内存模型", List.of(), traces::add)
        .collectList().block();

    assertThat(chunks).containsExactly("流式回答");
    assertThat(traces).hasSize(1);
    interview.guide.modules.knowledgebase.service.RagQueryExecution execution = traces.getFirst();
    assertThat(execution.outcome()).isEqualTo("ANSWERED");
    assertThat(execution.answer()).isEqualTo("流式回答");
    assertThat(execution.retrievedDocs()).hasSize(1);
    assertThat(execution.rewrittenQuestion()).isEqualTo("什么是 Java 内存模型");
    assertThat(execution.rewriteDurationMs()).isGreaterThanOrEqualTo(0);
    assertThat(execution.retrievalDurationMs()).isGreaterThanOrEqualTo(0);
    assertThat(execution.generationDurationMs()).isGreaterThanOrEqualTo(0);
  }

  @Nested
  @DisplayName("双路召回融合")
  class DualPathMerge {

    private org.springframework.ai.document.Document doc(String id, Double score) {
      return org.springframework.ai.document.Document.builder()
          .id(id).text("片段-" + id).score(score).build();
    }

    private KnowledgeBaseQueryService buildMergeService() throws Exception {
      KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
      properties.getSearch().setMergeOriginalQuery(true);
      return new KnowledgeBaseQueryService(llmProviderRegistry, vectorService, listService,
          countService, properties, resourceLoader);
    }

    private List<RagQueryExecution> answerCollect(KnowledgeBaseQueryService svc, String question)
        throws Exception {
      List<RagQueryExecution> traces = new ArrayList<>();
      svc.answerQuestionStream(List.of(1L), question, List.of(), traces::add)
          .collectList().block();
      return traces;
    }

    @Test
    @DisplayName("改写结果等于原问题：只调用一次向量检索")
    void shouldSearchOnceWhenRewriteEqualsOriginal() throws Exception {
      service = buildMergeService();
      mockPlainClient();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("什么是 HashMap");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("回答"));
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("d1", 0.9)));

      answerCollect(service, "什么是 HashMap");

      verify(vectorService, times(1)).similaritySearch(anyString(), anyList(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("改写结果与原问题不同：调用两次向量检索")
    void shouldSearchTwiceWhenRewriteDiffers() throws Exception {
      service = buildMergeService();
      mockPlainClient();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("改写后的 HashMap 原理问题");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("回答"));
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("d1", 0.9)));

      answerCollect(service, "什么是 HashMap");

      verify(vectorService, times(2)).similaritySearch(anyString(), anyList(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("两路命中相同 Document ID：去重保留一条且分数取更高值")
    void shouldDedupByDocumentIdKeepHigherScore() throws Exception {
      service = buildMergeService();
      mockPlainClient();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("改写问题");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("回答"));
      when(vectorService.similaritySearch(eq("改写问题"), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("shared", 0.6), doc("r1", 0.5)));
      when(vectorService.similaritySearch(eq("原始问题"), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("shared", 0.8)));

      List<RagQueryExecution> traces = answerCollect(service, "原始问题");

      List<RagQueryExecution.RetrievedDoc> docs = traces.getFirst().retrievedDocs();
      assertThat(docs).extracting(RagQueryExecution.RetrievedDoc::text)
          .containsExactly("片段-shared", "片段-r1");
      assertThat(docs.getFirst().score()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("改写路弱结果、原始路正确结果：正确结果进入最终 Top K")
    void shouldKeepOriginalPathCorrectResult() throws Exception {
      service = buildMergeService();
      mockPlainClient();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("改写跑偏的问题");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("回答"));
      when(vectorService.similaritySearch(eq("改写跑偏的问题"), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("weak1", 0.3), doc("weak2", 0.29)));
      when(vectorService.similaritySearch(eq("正确问题"), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("correct", 0.95)));

      List<RagQueryExecution> traces = answerCollect(service, "正确问题");

      assertThat(traces.getFirst().retrievedDocs().getFirst().text()).isEqualTo("片段-correct");
    }

    @Test
    @DisplayName("分数为 null：不抛异常且 null 分数排后有分数结果之后")
    void shouldSortNullScoresStable() throws Exception {
      service = buildMergeService();
      mockPlainClient();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("改写问题");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("回答"));
      when(vectorService.similaritySearch(eq("改写问题"), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("nullA", null)));
      when(vectorService.similaritySearch(eq("原始问题"), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("scored", 0.7), doc("nullB", null)));

      List<RagQueryExecution> traces = answerCollect(service, "原始问题");

      assertThat(traces.getFirst().retrievedDocs()).extracting(RagQueryExecution.RetrievedDoc::text)
          .containsExactly("片段-scored", "片段-nullA", "片段-nullB");
    }

    @Test
    @DisplayName("功能开关关闭：保持首个有效结果行为（改写命中即返回）")
    void shouldKeepFirstHitBehaviorWhenDisabled() throws Exception {
      service = buildService(true);   // 默认配置 mergeOriginalQuery=false
      mockPlainClient();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn("改写问题");
      when(plainChatClient.prompt().system(anyString()).user(anyString()).stream().content())
          .thenReturn(Flux.just("回答"));
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of(doc("d1", 0.9)));

      List<RagQueryExecution> traces = answerCollect(service, "原始问题");

      verify(vectorService, times(1)).similaritySearch(anyString(), anyList(), anyInt(), anyDouble());
      assertThat(traces.getFirst().attemptedQueries()).containsExactly("改写问题");
    }
  }

  @Nested
  @DisplayName("日志隐私")
  class LogPrivacy {

    private final Logger queryServiceLogger =
        (Logger) LoggerFactory.getLogger(KnowledgeBaseQueryService.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void setUpAppender() {
      logAppender.start();
      queryServiceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownAppender() {
      queryServiceLogger.detachAppender(logAppender);
    }

    private String capturedLogs() {
      StringBuilder sb = new StringBuilder();
      for (ILoggingEvent event : logAppender.list) {
        sb.append(event.getFormattedMessage()).append('\n');
      }
      return sb.toString();
    }

    @Test
    @DisplayName("同步问答日志不包含问题与改写原文，只记录长度和命中数")
    void shouldNotLogQuestionOrRewrittenQuery() throws Exception {
      String questionMarker = "问题敏感标记MARKER-Q-8f21";
      String rewriteMarker = "改写敏感标记MARKER-R-3c55";
      service = buildService(true);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenReturn(rewriteMarker);
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      service.answerQuestion(List.of(1L), questionMarker);

      String logs = capturedLogs();
      assertThat(logs).doesNotContain(questionMarker);
      assertThat(logs).doesNotContain(rewriteMarker);
      assertThat(logs).contains("questionLength=" + questionMarker.length());
      assertThat(logs).contains("rewrittenLength=" + rewriteMarker.length());
      assertThat(logs).contains("hits=1");
    }

    @Test
    @DisplayName("null 问题不抛 NPE 且返回无结果响应")
    void shouldReturnNoResultForNullQuestion() throws Exception {
      service = buildService(false);

      String answer = service.answerQuestion(List.of(1L), null);
      List<String> chunks = service.answerQuestionStream(List.of(1L), null).collectList().block();

      assertThat(answer).contains("未检索到相关信息");
      assertThat(chunks).hasSize(1);
      verify(llmProviderRegistry, never()).getPlainChatClient();
    }

    @Test
    @DisplayName("异常消息超长时日志只保留类名与截断消息")
    void shouldTruncateLongExceptionMessageInLogs() throws Exception {
      String tail = "敏感尾部标记MARKER-ERR-TAIL-1a2b3c4d5e6f";
      service = buildService(true);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenThrow(new IllegalStateException("x".repeat(500) + tail));
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      service.answerQuestion(List.of(1L), "正常问题");

      String logs = capturedLogs();
      assertThat(logs).doesNotContain(tail);
      assertThat(logs).contains("IllegalStateException: ");
      assertThat(logs).contains("…");
    }

    @Test
    @DisplayName("改写失败回退时异常日志保留错误消息且不包含问题原文")
    void shouldKeepErrorTypeWithoutQuestionText() throws Exception {
      String questionMarker = "问题敏感标记MARKER-Q-ERR-51d0";
      service = buildService(true);
      mockPlainClient();
      stubDocuments();
      when(plainChatClient.prompt().user(anyString()).call().content())
          .thenThrow(new IllegalStateException("LLM 连接超时"));
      when(plainChatClient.prompt().system(anyString()).user(anyString()).call().content())
          .thenReturn("同步回答");

      String answer = service.answerQuestion(List.of(1L), questionMarker);

      assertThat(answer).isEqualTo("同步回答");
      String logs = capturedLogs();
      assertThat(logs).doesNotContain(questionMarker);
      assertThat(logs).contains("Query rewrite 失败");
      assertThat(logs).contains("LLM 连接超时");
      ILoggingEvent warnEvent = logAppender.list.stream()
          .filter(event -> event.getLevel() == Level.WARN)
          .findFirst().orElse(null);
      assertThat(warnEvent).isNotNull();
      assertThat(warnEvent.getThrowableProxy()).isNotNull();
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
