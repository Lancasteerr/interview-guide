package interview.guide.modules.voiceinterview.context;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VoiceHistoryLoader 单元测试（P1-06）。
 *
 * <p>万条消息场景用 mock 断言"从不全量读取 + Pageable 有界"，不真插万行。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("语音历史有界加载器")
class VoiceHistoryLoaderTest {

  @Mock
  private VoiceInterviewMessageRepository messageRepository;
  @Mock
  private VoiceInterviewService interviewService;
  @Mock
  private VoiceContextCompressor compressor;

  private VoiceInterviewProperties properties;
  private VoiceHistoryLoader loader;

  @BeforeEach
  void setUp() {
    properties = new VoiceInterviewProperties();
    properties.getContextCompression().setEnabled(true);
    properties.getContextCompression().setWindowSize(20);
    properties.getContextCompression().setSummaryBatchSize(10);
    loader = new VoiceHistoryLoader(messageRepository, interviewService, compressor, properties);
  }

  private VoiceInterviewMessageEntity msg(int seq) {
    return VoiceInterviewMessageEntity.builder()
        .sequenceNum(seq).messageType("AI_SPEECH")
        .aiGeneratedText("问题" + seq).userRecognizedText("回答" + seq)
        .build();
  }

  private VoiceInterviewMessageEntity summaryRow(Integer seqNum, Integer boundary) {
    VoiceInterviewMessageEntity row = VoiceInterviewMessageEntity.builder()
        .sequenceNum(seqNum).messageType(VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)
        .build();
    row.setAiGeneratedText("已有摘要");
    row.setSummaryCoveredSequenceNum(boundary);
    return row;
  }

  private void stubWindow(int from, int count) {
    List<VoiceInterviewMessageEntity> desc = new java.util.ArrayList<>();
    for (int seq = from + count - 1; seq >= from; seq--) {
      desc.add(msg(seq));
    }
    when(messageRepository.findBySessionIdAndMessageTypeNotOrderBySequenceNumDesc(
        any(), anyString(), any(Pageable.class))).thenReturn(desc);
  }

  @Test
  @DisplayName("万条消息：单次加载从不全量读取，Pageable 大小不超过窗口+批次")
  void boundedReadsOnly() {
    stubWindow(9981, 20);   // 最近窗口 9981..10000
    when(interviewService.loadSummaryRow("1")).thenReturn(Optional.of(summaryRow(-1, 9980)));
    when(messageRepository.findBySessionIdAndMessageTypeNotAndSequenceNumGreaterThanAndSequenceNumLessThanOrderBySequenceNumAsc(
        any(), anyString(), eq(9980), eq(9981), any(Pageable.class))).thenReturn(List.of());
    when(compressor.compress(any(), anyString(), eq(0), isNull()))
        .thenReturn(new VoiceContextCompressor.CompressedHistory("已有摘要", List.of(), 0, false));
    when(compressor.formatRecent(any())).thenReturn(List.of("面试官：问题10000"));

    List<String> history = loader.loadHistory("1", null);

    assertThat(history).isNotEmpty();
    // 从不调用全量读取
    verify(messageRepository, never())
        .findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(any(), anyString());
    // 窗口查询 Pageable ≤ windowSize
    ArgumentCaptor<Pageable> windowPage = ArgumentCaptor.forClass(Pageable.class);
    verify(messageRepository).findBySessionIdAndMessageTypeNotOrderBySequenceNumDesc(
        any(), anyString(), windowPage.capture());
    assertThat(windowPage.getValue().getPageSize()).isLessThanOrEqualTo(20);
  }

  @Test
  @DisplayName("压缩成功推进边界：saveSummaryRow 收到单调递增的真实 sequenceNum")
  void advancesBoundaryWithRealSequenceNum() {
    stubWindow(31, 20);   // 窗口 31..50
    when(interviewService.loadSummaryRow("1")).thenReturn(Optional.of(summaryRow(-1, 20)));
    // pending 21..30（10 条，恰好一个批次）
    when(messageRepository.findBySessionIdAndMessageTypeNotAndSequenceNumGreaterThanAndSequenceNumLessThanOrderBySequenceNumAsc(
        any(), anyString(), eq(20), eq(31), any(Pageable.class)))
        .thenReturn(java.util.stream.IntStream.rangeClosed(21, 30).mapToObj(this::msg).toList());
    // compressor 摘要了全部 10 条 pending → coveredTurns=10
    when(compressor.compress(any(), anyString(), eq(0), isNull()))
        .thenReturn(new VoiceContextCompressor.CompressedHistory("新摘要", List.of(), 10, true));
    when(compressor.formatRecent(any())).thenReturn(List.of("面试官：问题50"));

    loader.loadHistory("1", null);

    // 新边界 = turns.get(9).sequenceNum = 30（pending 最后一条）
    verify(interviewService).saveSummaryRow("1", "新摘要", 30);
  }

  @Test
  @DisplayName("旧 SUMMARY 行（负 sequenceNum、新字段空）：OFFSET 定位第 N 条迁移，不全量读取")
  void migratesLegacyBoundaryByOffsetLookup() {
    stubWindow(17, 20);
    // 旧编码：sequenceNum=-16 → coveredTurns=15
    when(interviewService.loadSummaryRow("1")).thenReturn(Optional.of(summaryRow(-16, null)));
    when(messageRepository.findFirstBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
        any(), anyString(), any(Pageable.class))).thenReturn(Optional.of(msg(15)));
    when(messageRepository.findBySessionIdAndMessageTypeNotAndSequenceNumGreaterThanAndSequenceNumLessThanOrderBySequenceNumAsc(
        any(), anyString(), eq(15), eq(17), any(Pageable.class))).thenReturn(List.of());
    when(compressor.compress(any(), anyString(), eq(0), isNull()))
        .thenReturn(new VoiceContextCompressor.CompressedHistory("已有摘要", List.of(), 0, false));
    when(compressor.formatRecent(any())).thenReturn(List.of("面试官：问题36"));

    loader.loadHistory("1", null);

    // 迁移定位：OFFSET 14, LIMIT 1
    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(messageRepository).findFirstBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
        any(), anyString(), captor.capture());
    assertThat(captor.getValue().getOffset()).isEqualTo(14);
    assertThat(captor.getValue().getPageSize()).isEqualTo(1);
    // 全量读取从未发生
    verify(messageRepository, never())
        .findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(any(), anyString());
  }

  @Test
  @DisplayName("摘要/加载失败：降级为最近窗口，查询量仍有界且不恢复全量")
  void fallsBackToWindowOnFailure() {
    when(interviewService.loadSummaryRow("1")).thenThrow(new RuntimeException("db down"));
    stubWindow(81, 20);
    when(compressor.formatRecent(any())).thenReturn(List.of("面试官：问题100"));

    List<String> history = loader.loadHistory("1", null);

    assertThat(history).containsExactly("面试官：问题100");
    verify(messageRepository, never())
        .findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(any(), anyString());
    // 主路径在 SUMMARY 读取即失败，窗口查询只发生在降级路径（一次且有界）
    verify(messageRepository, times(1))
        .findBySessionIdAndMessageTypeNotOrderBySequenceNumDesc(any(), anyString(), any(Pageable.class));
  }

  @Test
  @DisplayName("enabled=false 保留全量分支（仅本地排查）")
  void uncompressedBranchWhenDisabled() {
    properties.getContextCompression().setEnabled(false);
    when(interviewService.getConversationHistory("1")).thenReturn(List.of(msg(1)));
    when(compressor.formatRecent(any())).thenReturn(List.of("面试官：问题1"));

    List<String> history = loader.loadHistory("1", null);

    assertThat(history).containsExactly("面试官：问题1");
    verify(interviewService).getConversationHistory("1");
    verify(messageRepository, never())
        .findBySessionIdAndMessageTypeNotOrderBySequenceNumDesc(any(), anyString(), any(Pageable.class));
  }

  @Nested
  @DisplayName("无 SUMMARY 行的场景")
  class NoSummaryRow {

    @Test
    @DisplayName("新会话（窗口内）：只查窗口，不查待摘要段，不持久化")
    void newSessionWindowOnly() {
      stubWindow(1, 5);
      when(interviewService.loadSummaryRow("1")).thenReturn(Optional.empty());
      when(compressor.compress(any(), isNull(), eq(0), isNull()))
          .thenReturn(new VoiceContextCompressor.CompressedHistory(null, List.of(), 0, false));
      when(compressor.formatRecent(any())).thenReturn(List.of("面试官：问题5"));

      List<String> history = loader.loadHistory("1", null);

      assertThat(history).containsExactly("面试官：问题5");
      verify(messageRepository, never())
          .findBySessionIdAndMessageTypeNotAndSequenceNumGreaterThanAndSequenceNumLessThanOrderBySequenceNumAsc(
              any(), anyString(), anyInt(), anyInt(), any(Pageable.class));
      verify(interviewService, never()).saveSummaryRow(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("旧长会话无 SUMMARY：covered=0 查待摘要段，每轮增量追上")
    void legacyLongSessionWithoutSummaryCatchesUpIncrementally() {
      stubWindow(31, 20);   // 窗口 31..50，之前 1..30 未摘要
      when(interviewService.loadSummaryRow("1")).thenReturn(Optional.empty());
      when(messageRepository.findBySessionIdAndMessageTypeNotAndSequenceNumGreaterThanAndSequenceNumLessThanOrderBySequenceNumAsc(
          any(), anyString(), eq(0), eq(31), any(Pageable.class)))
          .thenReturn(java.util.stream.IntStream.rangeClosed(1, 10).mapToObj(i -> msg(i * 3)).toList());
      when(compressor.compress(any(), isNull(), eq(0), isNull()))
          .thenReturn(new VoiceContextCompressor.CompressedHistory("首批摘要", List.of(), 10, true));
      when(compressor.formatRecent(any())).thenReturn(List.of("面试官：问题50"));

      loader.loadHistory("1", null);

      // 首批摘要边界 = pending 最后一条的 sequenceNum = 30
      verify(interviewService).saveSummaryRow(eq("1"), eq("首批摘要"), eq(30));
      verify(messageRepository, never())
          .findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(any(), anyString());
    }
  }
}
