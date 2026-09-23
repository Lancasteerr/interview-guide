package interview.guide.common.ai;

import interview.guide.common.ai.rerank.RerankReason;
import interview.guide.common.ai.rerank.RerankStatus;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.config.RerankProperties;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LlmProviderRegistry Rerank")
class LlmProviderRegistryRerankTest {

  private final List<Document> candidates = List.of(
      new Document("第一段"), new Document("第二段"));

  @Test
  @DisplayName("全局开关关闭时不读取 Provider")
  void disabledDoesNotResolveProvider() {
    LlmProviderProperties properties = mock(LlmProviderProperties.class);
    RerankProperties rerankProperties = new RerankProperties();
    LlmProviderRegistry registry = registry(properties, rerankProperties, null, null);

    var result = registry.rerankDocuments("问题", candidates);

    assertThat(result.status()).isEqualTo(RerankStatus.DISABLED);
    verify(properties, times(0)).getProviders();
  }

  @Test
  @DisplayName("legacy YAML 的非目标模型被跳过且 reload 清除解析缓存")
  void unsupportedYamlModelIsCachedUntilReload() {
    LlmProviderProperties properties = mock(LlmProviderProperties.class);
    ProviderConfig config = new ProviderConfig();
    config.setApiKey("test-key");
    config.setSupportsRerank(true);
    config.setRerankModel("qwen3-rerank");
    config.setRerankWorkspaceId("llm-test123");
    when(properties.getProviders()).thenReturn(Map.of("dashscope", config));
    RerankProperties rerankProperties = enabledProperties();
    LlmProviderRegistry registry = registry(properties, rerankProperties, null, null);

    assertThat(registry.rerankDocuments("问题", candidates).reason())
        .isEqualTo(RerankReason.UNSUPPORTED_MODEL);
    assertThat(registry.rerankDocuments("问题", candidates).reason())
        .isEqualTo(RerankReason.UNSUPPORTED_MODEL);
    verify(properties, times(1)).getProviders();

    registry.reload();
    registry.rerankDocuments("问题", candidates);
    verify(properties, times(2)).getProviders();
  }

  @Test
  @DisplayName("数据库 Provider 的非目标模型被安全跳过")
  void resolvesDatabaseProviderConfiguration() {
    LlmProviderProperties properties = new LlmProviderProperties();
    LlmProviderRepository repository = mock(LlmProviderRepository.class);
    LlmGlobalSettingRepository settingRepository = mock(LlmGlobalSettingRepository.class);
    ApiKeyEncryptionService encryptionService = mock(ApiKeyEncryptionService.class);
    LlmProviderEntity entity = LlmProviderEntity.builder()
        .id("dashscope")
        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
        .apiKeyCiphertext("cipher")
        .apiKeyNonce("nonce")
        .model("qwen3.5-flash")
        .supportsRerank(true)
        .rerankModel("qwen3-rerank")
        .rerankWorkspaceId("llm-test123")
        .enabled(true)
        .build();
    when(repository.findById("dashscope")).thenReturn(Optional.of(entity));
    when(encryptionService.decrypt("nonce", "cipher")).thenReturn("test-key");
    LlmProviderRegistry registry = new LlmProviderRegistry(
        properties, repository, settingRepository, encryptionService, enabledProperties(),
        null, null, null);

    var result = registry.rerankDocuments("问题", candidates);

    assertThat(result.status()).isEqualTo(RerankStatus.SKIPPED);
    assertThat(result.reason()).isEqualTo(RerankReason.UNSUPPORTED_MODEL);
  }

  private LlmProviderRegistry registry(
      LlmProviderProperties properties,
      RerankProperties rerankProperties,
      LlmProviderRepository repository,
      ApiKeyEncryptionService encryptionService) {
    return new LlmProviderRegistry(
        properties, repository, null, encryptionService, rerankProperties,
        null, null, null);
  }

  private RerankProperties enabledProperties() {
    RerankProperties properties = new RerankProperties();
    properties.setEnabled(true);
    return properties;
  }
}
