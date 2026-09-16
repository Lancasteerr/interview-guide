package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceInterviewSessionUrlTest {

  @Mock private RedissonClient redissonClient;
  @Mock private RBucket<VoiceInterviewSessionEntity> bucket;
  @InjectMocks private VoiceInterviewService service;

  @Test
  @DisplayName("会话返回相对 WebSocket 路径，不绑定后端主机和 HTTP 协议")
  void returnsDeploymentIndependentSocketPath() {
    when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(bucket);
    when(bucket.get()).thenReturn(VoiceInterviewSessionEntity.builder()
        .id(42L)
        .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
        .status(VoiceInterviewSessionStatus.IN_PROGRESS)
        .build());

    assertThat(service.getSessionDTO(42L).getWebSocketUrl())
        .isEqualTo("/ws/voice-interview/42");
  }
}
