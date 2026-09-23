package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.recovery.VectorizeRecoveryProperties;
import interview.guide.modules.knowledgebase.entity.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("向量化卡住任务恢复调度器")
class VectorizeRecoverySchedulerTest {

  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock
  private VectorizeStreamProducer producer;

  private VectorizeRecoveryProperties properties;
  private VectorizeRecoveryScheduler scheduler;

  @BeforeEach
  void setUp() {
    properties = new VectorizeRecoveryProperties();
    scheduler = new VectorizeRecoveryScheduler(knowledgeBaseRepository, producer, properties);
  }

  private KnowledgeBaseEntity kb(long id, int recoveryCount) {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(id);
    kb.setVectorRecoveryCount(recoveryCount);
    return kb;
  }

  @Test
  @DisplayName("PENDING 超时：touch 原子推进后补投一次并累加恢复计数")
  void recoversStalePendingOnce() {
    when(knowledgeBaseRepository.findStaleByVectorStatus(eq(VectorStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of(kb(1L, 0)));
    when(knowledgeBaseRepository.findStaleByVectorStatus(eq(VectorStatus.PROCESSING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of());
    when(knowledgeBaseRepository.touchQueuedVectorForRecovery(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(1);
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb(1L, 1)));
    when(producer.sendVectorizeTask(1L)).thenReturn(true);

    scheduler.recoverStuckTasks();

    verify(knowledgeBaseRepository).incrementVectorRecoveryCount(1L);
    verify(producer).sendVectorizeTask(1L);
    // 扫描带每轮上限
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(knowledgeBaseRepository).findStaleByVectorStatus(eq(VectorStatus.PENDING), any(LocalDateTime.class), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(properties.getBatchSize());
  }

  @Test
  @DisplayName("touch 未推进（已被其他周期恢复）：不再补投")
  void skipsWhenTouchFails() {
    when(knowledgeBaseRepository.findStaleByVectorStatus(eq(VectorStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of(kb(1L, 0)));
    when(knowledgeBaseRepository.findStaleByVectorStatus(eq(VectorStatus.PROCESSING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of());
    when(knowledgeBaseRepository.touchQueuedVectorForRecovery(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(0);

    scheduler.recoverStuckTasks();

    verify(producer, never()).sendVectorizeTask(anyLong());
  }

  @Test
  @DisplayName("PROCESSING 无进展超时：条件重置成功后补投")
  void resetsStaleProcessingThenRecovers() {
    when(knowledgeBaseRepository.findStaleByVectorStatus(eq(VectorStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of());
    when(knowledgeBaseRepository.findStaleByVectorStatus(eq(VectorStatus.PROCESSING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of(kb(2L, 0)));
    when(knowledgeBaseRepository.resetStaleVectorProcessing(eq(2L), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(1);
    when(knowledgeBaseRepository.findById(2L)).thenReturn(Optional.of(kb(2L, 1)));
    when(producer.sendVectorizeTask(2L)).thenReturn(true);

    scheduler.recoverStuckTasks();

    verify(producer).sendVectorizeTask(2L);
  }

  @Test
  @DisplayName("恢复次数达到上限：转 FAILED 不再补投")
  void failsAfterMaxRecoveryCount() {
    when(knowledgeBaseRepository.findStaleByVectorStatus(eq(VectorStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of(kb(3L, properties.getMaxRecoveryCount() - 1)));
    when(knowledgeBaseRepository.findStaleByVectorStatus(eq(VectorStatus.PROCESSING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of());
    when(knowledgeBaseRepository.touchQueuedVectorForRecovery(eq(3L), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(1);
    when(knowledgeBaseRepository.findById(3L)).thenReturn(Optional.of(kb(3L, properties.getMaxRecoveryCount())));

    scheduler.recoverStuckTasks();

    verify(producer, never()).sendVectorizeTask(anyLong());
    verify(knowledgeBaseRepository).failVectorIfPending(
        eq(3L), anyString(), any(LocalDateTime.class));
  }

  @Test
  @DisplayName("开关关闭：不扫描不补投")
  void disabledDoesNothing() {
    properties.setEnabled(false);

    scheduler.recoverStuckTasks();

    verify(knowledgeBaseRepository, never()).findStaleByVectorStatus(any(), any(), any());
  }
}
