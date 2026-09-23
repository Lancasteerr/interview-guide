package interview.guide.modules.llmprovider.service;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.llmprovider.entity.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.entity.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LLM Provider 初始化")
class LlmProviderBootstrapServiceTest {

  @Mock
  private LlmProviderRepository providerRepository;
  @Mock
  private LlmGlobalSettingRepository globalSettingRepository;
  @Mock
  private ApiKeyEncryptionService encryptionService;

  @Test
  @DisplayName("空库初始化时写入 DashScope Rerank 配置")
  void seedsDashscopeRerankConfiguration() {
    LlmProviderProperties properties = new LlmProviderProperties();
    LlmProviderProperties.ProviderConfig dashscope = new LlmProviderProperties.ProviderConfig();
    dashscope.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
    dashscope.setApiKey("secret");
    dashscope.setModel("qwen3.5-flash");
    dashscope.setSupportsRerank(true);
    dashscope.setRerankModel(" qwen3.7-text-rerank ");
    dashscope.setRerankWorkspaceId(" workspace-123 ");
    properties.setProviders(Map.of("dashscope", dashscope));

    when(providerRepository.count()).thenReturn(0L);
    when(globalSettingRepository.existsById(LlmGlobalSettingEntity.SINGLETON_ID))
        .thenReturn(true);
    when(encryptionService.encrypt(anyString()))
        .thenReturn(new ApiKeyEncryptionService.EncryptedValue("nonce", "ciphertext"));

    LlmProviderBootstrapService service = new LlmProviderBootstrapService(
        properties, providerRepository, globalSettingRepository, encryptionService);
    service.seedProvidersIfNecessary();

    ArgumentCaptor<LlmProviderEntity> captor = ArgumentCaptor.forClass(LlmProviderEntity.class);
    verify(providerRepository).save(captor.capture());
    LlmProviderEntity saved = captor.getValue();
    assertThat(saved.isSupportsRerank()).isTrue();
    assertThat(saved.getRerankModel()).isEqualTo("qwen3.7-text-rerank");
    assertThat(saved.getRerankWorkspaceId()).isEqualTo("workspace-123");
  }
}
