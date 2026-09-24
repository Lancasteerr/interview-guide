package interview.guide.common.ai;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.modules.llmprovider.entity.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.entity.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import lombok.extern.slf4j.Slf4j;

/**
 * Provider 配置解析器。
 *
 * <p>统一处理数据库配置、旧版 YAML 配置和默认 Provider 选择，客户端注册表不再承担配置来源判断。</p>
 */
@Slf4j
final class LlmProviderResolver {

  private final LlmProviderProperties properties;
  private final LlmProviderRepository providerRepository;
  private final LlmGlobalSettingRepository globalSettingRepository;
  private final ApiKeyEncryptionService encryptionService;

  LlmProviderResolver(
      LlmProviderProperties properties,
      LlmProviderRepository providerRepository,
      LlmGlobalSettingRepository globalSettingRepository,
      ApiKeyEncryptionService encryptionService) {
    this.properties = properties;
    this.providerRepository = providerRepository;
    this.globalSettingRepository = globalSettingRepository;
    this.encryptionService = encryptionService;
  }

  String resolveProviderId(String providerId) {
    if (providerId == null || providerId.isBlank() || "default".equalsIgnoreCase(providerId.trim())) {
      return resolveDefaultChatProviderId();
    }
    return providerId;
  }

  String resolveDefaultChatProviderId() {
    if (globalSettingRepository == null) {
      return properties.getDefaultProvider();
    }
    return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
        .map(LlmGlobalSettingEntity::getDefaultChatProviderId)
        .filter(id -> !isBlank(id))
        .orElse(properties.getDefaultProvider());
  }

  String resolveDefaultEmbeddingProviderId() {
    if (globalSettingRepository == null) {
      return !isBlank(properties.getDefaultEmbeddingProvider())
          ? properties.getDefaultEmbeddingProvider()
          : properties.getDefaultProvider();
    }
    return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
        .map(LlmGlobalSettingEntity::getDefaultEmbeddingProviderId)
        .filter(id -> !isBlank(id))
        .orElseGet(() -> !isBlank(properties.getDefaultEmbeddingProvider())
            ? properties.getDefaultEmbeddingProvider()
            : properties.getDefaultProvider());
  }

  ProviderSnapshot loadProviderOrThrow(String providerId) {
    if (providerRepository == null) {
      return loadProviderFromPropertiesOrThrow(providerId);
    }
    LlmProviderEntity entity = providerRepository.findById(providerId)
        .filter(LlmProviderEntity::isEnabled)
        .orElseThrow(() -> new IllegalArgumentException("Unknown LLM provider: " + providerId));
    return new ProviderSnapshot(
        entity.getId(),
        entity.getBaseUrl(),
        encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext()),
        entity.getModel(),
        entity.getEmbeddingModel(),
        entity.getEmbeddingDimensions(),
        entity.isSupportsEmbedding(),
        entity.getRerankModel(),
        entity.getRerankWorkspaceId(),
        entity.isSupportsRerank(),
        entity.getTemperature()
    );
  }

  Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    return properties.getEmbeddingDimensions();
  }

  boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private ProviderSnapshot loadProviderFromPropertiesOrThrow(String providerId) {
    ProviderConfig config = properties.getProviders().get(providerId);
    if (config == null) {
      log.error("[LlmProviderRegistry] Provider config not found: {}", providerId);
      throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
    }
    boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
        || !isBlank(config.getEmbeddingModel());
    return new ProviderSnapshot(
        providerId,
        config.getBaseUrl(),
        config.getApiKey(),
        config.getModel(),
        config.getEmbeddingModel(),
        config.getEmbeddingDimensions(),
        supportsEmbedding,
        config.getRerankModel(),
        config.getRerankWorkspaceId(),
        Boolean.TRUE.equals(config.getSupportsRerank()),
        config.getTemperature()
    );
  }

  record ProviderSnapshot(
      String id,
      String baseUrl,
      String apiKey,
      String model,
      String embeddingModel,
      Integer embeddingDimensions,
      boolean supportsEmbedding,
      String rerankModel,
      String rerankWorkspaceId,
      boolean supportsRerank,
      Double temperature
  ) {
  }
}
