package interview.guide.modules.llmprovider.service;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderBootstrapService {

  private static final String DASHSCOPE_PROVIDER_ID = "dashscope";
  private static final int MAX_RERANK_CONFIG_LENGTH = 128;

  private final LlmProviderProperties properties;
  private final LlmProviderRepository providerRepository;
  private final LlmGlobalSettingRepository globalSettingRepository;
  private final ApiKeyEncryptionService encryptionService;

  @PostConstruct
  @Transactional
  public void seedProvidersIfNecessary() {
    if (providerRepository.count() == 0) {
      seedProviders();
    }
    ensureGlobalSetting();
  }

  private void seedProviders() {
    Map<String, ProviderConfig> providers = properties.getProviders();
    if (providers == null || providers.isEmpty()) {
      log.warn("No app.ai.providers seed configuration found");
      return;
    }

    providers.forEach((id, config) -> {
      if (isBlank(id) || config == null || isBlank(config.getBaseUrl()) || isBlank(config.getModel())) {
        log.warn("Skip invalid provider seed: id={}", id);
        return;
      }
      ApiKeyEncryptionService.EncryptedValue encrypted =
          encryptionService.encrypt(config.getApiKey() != null ? config.getApiKey() : "");
      boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
          || !isBlank(config.getEmbeddingModel());
      boolean supportsRerank = Boolean.TRUE.equals(config.getSupportsRerank());
      String rerankModel = supportsRerank ? trimOrNull(config.getRerankModel()) : null;
      String rerankWorkspaceId = supportsRerank
          ? trimOrNull(config.getRerankWorkspaceId()) : null;
      validateRerankSeed(id, config, supportsRerank, rerankModel, rerankWorkspaceId);

      LlmProviderEntity entity = LlmProviderEntity.builder()
          .id(id)
          .baseUrl(config.getBaseUrl())
          .apiKeyNonce(encrypted.nonce())
          .apiKeyCiphertext(encrypted.ciphertext())
          .model(config.getModel())
          .embeddingModel(trimOrNull(config.getEmbeddingModel()))
          .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))
          .supportsEmbedding(supportsEmbedding)
          .rerankModel(rerankModel)
          .rerankWorkspaceId(rerankWorkspaceId)
          .supportsRerank(supportsRerank)
          .temperature(config.getTemperature())
          .enabled(true)
          .builtin(true)
          .build();
      providerRepository.save(entity);
    });
    log.info("Seeded {} LLM providers from application configuration", providerRepository.count());
  }

  private void ensureGlobalSetting() {
    if (globalSettingRepository.existsById(LlmGlobalSettingEntity.SINGLETON_ID)) {
      return;
    }
    String defaultChatProvider = resolveExistingProvider(
        properties.getDefaultProvider(),
        providerRepository.findAll().stream().findFirst().map(LlmProviderEntity::getId).orElse("dashscope")
    );
    String configuredEmbeddingProvider = !isBlank(properties.getDefaultEmbeddingProvider())
        ? properties.getDefaultEmbeddingProvider()
        : defaultChatProvider;
    String defaultEmbeddingProvider = resolveExistingEmbeddingProvider(configuredEmbeddingProvider, defaultChatProvider);

    globalSettingRepository.save(LlmGlobalSettingEntity.builder()
        .id(LlmGlobalSettingEntity.SINGLETON_ID)
        .defaultChatProviderId(defaultChatProvider)
        .defaultEmbeddingProviderId(defaultEmbeddingProvider)
        .build());
    log.info("Initialized LLM global setting: chatProvider={}, embeddingProvider={}",
        defaultChatProvider, defaultEmbeddingProvider);
  }

  private String resolveExistingProvider(String preferredProvider, String fallbackProvider) {
    if (!isBlank(preferredProvider) && providerRepository.existsById(preferredProvider)) {
      return preferredProvider;
    }
    return fallbackProvider;
  }

  private String resolveExistingEmbeddingProvider(String preferredProvider, String fallbackProvider) {
    return providerRepository.findById(preferredProvider)
        .filter(this::canProvideEmbedding)
        .map(LlmProviderEntity::getId)
        .orElseGet(() -> providerRepository.findAll().stream()
            .filter(this::canProvideEmbedding)
            .findFirst()
            .map(LlmProviderEntity::getId)
            .orElse(fallbackProvider));
  }

  private boolean canProvideEmbedding(LlmProviderEntity provider) {
    return provider.isEnabled()
        && provider.isSupportsEmbedding()
        && !isBlank(provider.getEmbeddingModel());
  }

  private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    return properties.getEmbeddingDimensions();
  }

  private void validateRerankSeed(String providerId, ProviderConfig config,
                                  boolean supportsRerank, String rerankModel,
                                  String rerankWorkspaceId) {
    boolean hasRerankConfig = supportsRerank
        || !isBlank(config.getRerankModel())
        || !isBlank(config.getRerankWorkspaceId());
    if (!DASHSCOPE_PROVIDER_ID.equalsIgnoreCase(providerId) && hasRerankConfig) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "当前仅 DashScope Provider 支持配置 Rerank");
    }
    if (!supportsRerank && hasRerankConfig) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "启用 Rerank 后才能配置模型和 Workspace ID");
    }
    if (supportsRerank && (rerankModel == null || rerankWorkspaceId == null)) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "DashScope Rerank 配置必须同时包含模型和 Workspace ID");
    }
    if (rerankModel != null && rerankModel.length() > MAX_RERANK_CONFIG_LENGTH) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "rerankModel 长度不能超过 " + MAX_RERANK_CONFIG_LENGTH);
    }
    if (rerankWorkspaceId != null
        && rerankWorkspaceId.length() > MAX_RERANK_CONFIG_LENGTH) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "rerankWorkspaceId 长度不能超过 " + MAX_RERANK_CONFIG_LENGTH);
    }
  }

  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
