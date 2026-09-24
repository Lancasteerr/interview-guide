package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.entity.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.entity.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;

import java.util.Map;
import java.util.function.Consumer;

/**
 * LLM 默认聊天 Provider 和默认 Embedding Provider 的状态管理。
 */
final class LlmProviderDefaultService {

  private final LlmProviderProperties properties;
  private final LlmProviderRegistry registry;
  private final LlmProviderRepository providerRepository;
  private final LlmGlobalSettingRepository globalSettingRepository;
  private final Consumer<String> legacyDefaultWriter;

  LlmProviderDefaultService(
      LlmProviderProperties properties,
      LlmProviderRegistry registry,
      LlmProviderRepository providerRepository,
      LlmGlobalSettingRepository globalSettingRepository,
      Consumer<String> legacyDefaultWriter) {
    this.properties = properties;
    this.registry = registry;
    this.providerRepository = providerRepository;
    this.globalSettingRepository = globalSettingRepository;
    this.legacyDefaultWriter = legacyDefaultWriter;
  }

  DefaultProviderDTO getDefaultProvider() {
    if (!isDatabaseBacked()) {
      return new DefaultProviderDTO(
          properties.getDefaultProvider(), properties.getDefaultEmbeddingProvider());
    }
    LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
    return new DefaultProviderDTO(
        setting.getDefaultChatProviderId(), setting.getDefaultEmbeddingProviderId());
  }

  void updateDefaultProvider(DefaultProviderDTO request) {
    if (!isDatabaseBacked()) {
      updateDefaultProviderLegacy(request);
      return;
    }
    String providerId = trimOrNull(request.defaultProvider());
    if (providerId == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultProvider 不能为空");
    }
    getProviderEntityOrThrow(providerId);
    LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
    setting.setDefaultChatProviderId(providerId);
    globalSettingRepository.save(setting);
    registry.reload();
  }

  void updateDefaultEmbeddingProvider(DefaultProviderDTO request) {
    String providerId = trimOrNull(request.defaultEmbeddingProvider());
    if (providerId == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultEmbeddingProvider 不能为空");
    }
    LlmProviderEntity provider = getProviderEntityOrThrow(providerId);
    String embeddingModel = trimOrNull(provider.getEmbeddingModel());
    if (!provider.isSupportsEmbedding() || embeddingModel == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "Provider '" + providerId + "' 不支持 Embedding，不能设为默认向量服务");
    }
    Integer embeddingDimensions = provider.getEmbeddingDimensions();
    if (embeddingDimensions == null || embeddingDimensions <= 0) {
      embeddingDimensions = properties.getEmbeddingDimensions();
    }
    if (embeddingDimensions == null || embeddingDimensions <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "向量维度必须为正整数");
    }
    LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
    setting.setDefaultEmbeddingProviderId(providerId);
    globalSettingRepository.save(setting);
    registry.reload();
  }

  private void updateDefaultProviderLegacy(DefaultProviderDTO request) {
    String providerId = trimOrNull(request.defaultProvider());
    if (providerId == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultProvider 不能为空");
    }
    getLegacyProviderConfigOrThrow(providerId);
    properties.setDefaultProvider(providerId);
    legacyDefaultWriter.accept(providerId);
    registry.reload();
  }

  private boolean isDatabaseBacked() {
    return providerRepository != null && globalSettingRepository != null;
  }

  private ProviderConfig getLegacyProviderConfigOrThrow(String id) {
    Map<String, ProviderConfig> providers = properties.getProviders();
    if (providers == null) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "Provider 配置未初始化");
    }
    ProviderConfig config = providers.get(id);
    if (config == null) {
      throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
          "Provider '" + id + "' 不存在");
    }
    return config;
  }

  private LlmProviderEntity getProviderEntityOrThrow(String id) {
    return providerRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
            "Provider '" + id + "' 不存在"));
  }

  private LlmGlobalSettingEntity getGlobalSettingOrThrow() {
    return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
            "默认 Provider 配置未初始化"));
  }

  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }
}
