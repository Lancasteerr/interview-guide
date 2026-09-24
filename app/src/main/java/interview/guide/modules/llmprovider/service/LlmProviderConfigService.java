package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.AsrConfigDTO;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.TtsConfigDTO;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.llmprovider.entity.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.entity.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
@Slf4j
public class LlmProviderConfigService {

  private static final String DASHSCOPE_PROVIDER_ID = "dashscope";
  private static final int MAX_RERANK_CONFIG_LENGTH = 128;

  private final LlmProviderProperties properties;
  private final LlmProviderRegistry registry;
  private final LlmProviderRepository providerRepository;
  private final LlmGlobalSettingRepository globalSettingRepository;
  private final ApiKeyEncryptionService encryptionService;
  private final LlmProviderConfigFileService configFileService;
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
  private final VoiceInterviewProperties voiceProperties;
  private final QwenAsrService asrService;
  private final QwenTtsService ttsService;
  private final LlmProviderConnectivityTester connectivityTester;
  private final VoiceProviderConfigService voiceConfigService;
  private final LlmProviderDefaultService defaultService;

  private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
      "dashscope", "text-embedding-v3",
      "glm", "embedding-3",
      "zhipu", "embedding-3",
      "baidu", "Embedding-V1",
      "minimax", "embo-01"
  );

  @Autowired
  public LlmProviderConfigService(
      LlmProviderProperties properties,
      LlmProviderRegistry registry,
      LlmProviderRepository providerRepository,
      LlmGlobalSettingRepository globalSettingRepository,
      ApiKeyEncryptionService encryptionService,
      VoiceInterviewProperties voiceProperties,
      QwenAsrService asrService,
      QwenTtsService ttsService) {
    this.properties = properties;
    this.registry = registry;
    this.providerRepository = providerRepository;
    this.globalSettingRepository = globalSettingRepository;
    this.encryptionService = encryptionService;
    this.configFileService = new LlmProviderConfigFileService(
        properties.getConfigYamlPath(), properties.getConfigEnvPath());
    this.voiceProperties = voiceProperties;
    this.asrService = asrService;
    this.ttsService = ttsService;
    this.connectivityTester = new LlmProviderConnectivityTester();
    this.voiceConfigService = new VoiceProviderConfigService(
      voiceProperties, asrService, ttsService,
      properties.getConfigYamlPath(), properties.getConfigEnvPath(), this::maskApiKey);
    this.defaultService = new LlmProviderDefaultService(
        properties, registry, providerRepository, globalSettingRepository,
        this::writeDefaultProviderToYaml);
  }

  public LlmProviderConfigService(
      LlmProviderProperties properties,
      LlmProviderRegistry registry,
      VoiceInterviewProperties voiceProperties,
      QwenAsrService asrService,
      QwenTtsService ttsService) {
    this(properties, registry, null, null, null, voiceProperties, asrService, ttsService);
  }

  @PostConstruct
  void validateWritablePaths() {
    configFileService.validateWritablePaths();
  }

  // ===== Read operations (read lock) =====

  public List<ProviderDTO> listProviders() {
    rwLock.readLock().lock();
    try {
      if (!isDatabaseBacked()) {
        Map<String, ProviderConfig> providers = properties.getProviders();
        if (providers == null) return List.of();
        return providers.entrySet().stream()
            .map(e -> ProviderDTO.builder()
                .id(e.getKey())
                .baseUrl(e.getValue().getBaseUrl())
                .maskedApiKey(maskApiKey(e.getValue().getApiKey()))
                .model(e.getValue().getModel())
                .embeddingModel(e.getValue().getEmbeddingModel())
                .embeddingDimensions(resolveEmbeddingDimensions(e.getValue().getEmbeddingDimensions()))
                .supportsEmbedding(Boolean.TRUE.equals(e.getValue().getSupportsEmbedding())
                    || trimOrNull(e.getValue().getEmbeddingModel()) != null)
                .rerankModel(e.getValue().getRerankModel())
                .rerankWorkspaceId(e.getValue().getRerankWorkspaceId())
                .supportsRerank(Boolean.TRUE.equals(e.getValue().getSupportsRerank()))
                .temperature(e.getValue().getTemperature())
                .defaultChatProvider(e.getKey().equals(properties.getDefaultProvider()))
                .defaultEmbeddingProvider(e.getKey().equals(properties.getDefaultEmbeddingProvider()))
                .build())
            .toList();
      }
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      return providerRepository.findAll().stream()
          .map(provider -> ProviderDTO.builder()
              .id(provider.getId())
              .baseUrl(provider.getBaseUrl())
              .maskedApiKey(maskApiKey(decryptApiKey(provider)))
              .model(provider.getModel())
              .embeddingModel(provider.getEmbeddingModel())
              .embeddingDimensions(resolveEmbeddingDimensions(provider.getEmbeddingDimensions()))
              .supportsEmbedding(provider.isSupportsEmbedding())
              .rerankModel(provider.getRerankModel())
              .rerankWorkspaceId(provider.getRerankWorkspaceId())
              .supportsRerank(provider.isSupportsRerank())
              .temperature(provider.getTemperature())
              .defaultChatProvider(provider.getId().equals(setting.getDefaultChatProviderId()))
              .defaultEmbeddingProvider(provider.getId().equals(setting.getDefaultEmbeddingProviderId()))
              .build())
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderDTO getProvider(String id) {
    rwLock.readLock().lock();
    try {
      if (!isDatabaseBacked()) {
        ProviderConfig config = getLegacyProviderConfigOrThrow(id);
        return ProviderDTO.builder()
            .id(id)
            .baseUrl(config.getBaseUrl())
            .maskedApiKey(maskApiKey(config.getApiKey()))
            .model(config.getModel())
            .embeddingModel(config.getEmbeddingModel())
            .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))
            .supportsEmbedding(Boolean.TRUE.equals(config.getSupportsEmbedding())
                || trimOrNull(config.getEmbeddingModel()) != null)
            .rerankModel(config.getRerankModel())
            .rerankWorkspaceId(config.getRerankWorkspaceId())
            .supportsRerank(Boolean.TRUE.equals(config.getSupportsRerank()))
            .temperature(config.getTemperature())
            .defaultChatProvider(id.equals(properties.getDefaultProvider()))
            .defaultEmbeddingProvider(id.equals(properties.getDefaultEmbeddingProvider()))
            .build();
      }
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      LlmProviderEntity provider = getProviderEntityOrThrow(id);
      return ProviderDTO.builder()
          .id(id)
          .baseUrl(provider.getBaseUrl())
          .maskedApiKey(maskApiKey(decryptApiKey(provider)))
          .model(provider.getModel())
          .embeddingModel(provider.getEmbeddingModel())
          .embeddingDimensions(resolveEmbeddingDimensions(provider.getEmbeddingDimensions()))
          .supportsEmbedding(provider.isSupportsEmbedding())
          .rerankModel(provider.getRerankModel())
          .rerankWorkspaceId(provider.getRerankWorkspaceId())
          .supportsRerank(provider.isSupportsRerank())
          .temperature(provider.getTemperature())
          .defaultChatProvider(id.equals(setting.getDefaultChatProviderId()))
          .defaultEmbeddingProvider(id.equals(setting.getDefaultEmbeddingProviderId()))
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public DefaultProviderDTO getDefaultProvider() {
    rwLock.readLock().lock();
    try {
      return defaultService.getDefaultProvider();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public AsrConfigDTO getAsrConfig() {
    rwLock.readLock().lock();
    try {
      return voiceConfigService.getAsrConfig();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public TtsConfigDTO getTtsConfig() {
    rwLock.readLock().lock();
    try {
      return voiceConfigService.getTtsConfig();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderTestResult testProvider(String id) {
    rwLock.readLock().lock();
    try {
      ProviderRuntimeConfig config = isDatabaseBacked()
          ? getProviderRuntimeConfigOrThrow(id)
          : toRuntimeConfig(getLegacyProviderConfigOrThrow(id));
      return doTestProvider(config, id);
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderTestResult testAsrConfig() {
    rwLock.readLock().lock();
    try {
      return voiceConfigService.testAsrConfig();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  // ===== Write operations (write lock) =====

  @Transactional
  public void createProvider(CreateProviderRequest request) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        createProviderLegacy(request);
        return;
      }
      String providerId = trimOrNull(request.id());
      if (providerRepository.existsById(providerId)) {
        throw new BusinessException(ErrorCode.PROVIDER_ALREADY_EXISTS,
            "Provider '" + request.id() + "' 已存在");
      }
      String baseUrl = requireNonBlank(request.baseUrl(), "baseUrl");
      String model = requireNonBlank(request.model(), "model");
      String apiKey = requireNonBlank(request.apiKey(), "apiKey");
      String embeddingModel = trimOrNull(request.embeddingModel());
      Integer embeddingDimensions = resolveEmbeddingDimensions(request.embeddingDimensions());
      boolean supportsEmbedding = request.supportsEmbedding() != null
          ? request.supportsEmbedding()
          : embeddingModel != null;
      validateEmbeddingConfig(providerId, supportsEmbedding, embeddingModel, embeddingDimensions);
      String rerankModel = trimOrNull(request.rerankModel());
      String rerankWorkspaceId = trimOrNull(request.rerankWorkspaceId());
      boolean supportsRerank = Boolean.TRUE.equals(request.supportsRerank());
      validateRerankConfig(providerId, supportsRerank, rerankModel, rerankWorkspaceId);

      ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(apiKey);
      providerRepository.save(LlmProviderEntity.builder()
          .id(providerId)
          .baseUrl(baseUrl)
          .apiKeyNonce(encrypted.nonce())
          .apiKeyCiphertext(encrypted.ciphertext())
          .model(model)
          .embeddingModel(embeddingModel)
          .embeddingDimensions(embeddingDimensions)
          .supportsEmbedding(supportsEmbedding)
          .rerankModel(supportsRerank ? rerankModel : null)
          .rerankWorkspaceId(supportsRerank ? rerankWorkspaceId : null)
          .supportsRerank(supportsRerank)
          .temperature(request.temperature())
          .enabled(true)
          .builtin(false)
          .build());
      registry.reload();
      log.info("Created provider: id={}, baseUrl={}, model={}", providerId, baseUrl, model);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateProvider(String id, UpdateProviderRequest request) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        updateProviderLegacy(id, request);
        return;
      }
      LlmProviderEntity provider = getProviderEntityOrThrow(id);

      String trimmedBaseUrl = trimOrNull(request.baseUrl());
      if (request.baseUrl() != null && trimmedBaseUrl == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空字符串");
      }
      String trimmedModel = trimOrNull(request.model());
      if (request.model() != null && trimmedModel == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空字符串");
      }
      String trimmedApiKey = trimOrNull(request.apiKey());
      if (request.apiKey() != null && trimmedApiKey == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串");
      }

      if (trimmedBaseUrl != null) provider.setBaseUrl(trimmedBaseUrl);
      if (trimmedModel != null) provider.setModel(trimmedModel);
      if (request.embeddingModel() != null) {
        provider.setEmbeddingModel(trimOrNull(request.embeddingModel()));
      }
      if (request.embeddingDimensions() != null) {
        provider.setEmbeddingDimensions(resolveEmbeddingDimensions(request.embeddingDimensions()));
      }
      if (request.supportsEmbedding() != null) {
        provider.setSupportsEmbedding(request.supportsEmbedding());
      }
      validateEmbeddingConfig(
          id,
          provider.isSupportsEmbedding(),
          provider.getEmbeddingModel(),
          resolveEmbeddingDimensions(provider.getEmbeddingDimensions()));
      boolean targetSupportsRerank = request.supportsRerank() != null
          ? request.supportsRerank() : provider.isSupportsRerank();
      boolean disablingRerank = Boolean.FALSE.equals(request.supportsRerank());
      String targetRerankModel = request.rerankModel() != null
          ? trimOrNull(request.rerankModel())
          : disablingRerank ? null : provider.getRerankModel();
      String targetRerankWorkspaceId = request.rerankWorkspaceId() != null
          ? trimOrNull(request.rerankWorkspaceId())
          : disablingRerank ? null : provider.getRerankWorkspaceId();
      validateRerankConfig(
          id, targetSupportsRerank, targetRerankModel, targetRerankWorkspaceId);
      provider.setSupportsRerank(targetSupportsRerank);
      provider.setRerankModel(targetSupportsRerank ? targetRerankModel : null);
      provider.setRerankWorkspaceId(targetSupportsRerank ? targetRerankWorkspaceId : null);
      if (request.temperature() != null) {
        provider.setTemperature(request.temperature());
      }
      if (trimmedApiKey != null) {
        ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(trimmedApiKey);
        provider.setApiKeyNonce(encrypted.nonce());
        provider.setApiKeyCiphertext(encrypted.ciphertext());
      }

      providerRepository.save(provider);
      registry.reload();
      log.info("Updated provider: id={}", id);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void deleteProvider(String id) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        deleteProviderLegacy(id);
        return;
      }
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      if (id.equals(setting.getDefaultChatProviderId()) || id.equals(setting.getDefaultEmbeddingProviderId())) {
        throw new BusinessException(ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
            "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider");
      }
      getProviderEntityOrThrow(id);

      providerRepository.deleteById(id);
      registry.reload();
      log.info("Deleted provider: id={}", id);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateDefaultProvider(DefaultProviderDTO request) {
    rwLock.writeLock().lock();
    try {
      defaultService.updateDefaultProvider(request);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateDefaultEmbeddingProvider(DefaultProviderDTO request) {
    rwLock.writeLock().lock();
    try {
      defaultService.updateDefaultEmbeddingProvider(request);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  public void updateAsrConfig(AsrConfigRequest request) {
    rwLock.writeLock().lock();
    try {
      voiceConfigService.updateAsrConfig(request);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  public void updateTtsConfig(TtsConfigRequest request) {
    rwLock.writeLock().lock();
    try {
      voiceConfigService.updateTtsConfig(request);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  public void reloadProviders() {
    registry.reload();
    log.info("Manual provider reload triggered");
  }

  // ===== Internal helpers =====

  private boolean isDatabaseBacked() {
    return providerRepository != null && globalSettingRepository != null && encryptionService != null;
  }

  private Map<String, ProviderConfig> getLegacyProvidersOrThrow() {
    Map<String, ProviderConfig> providers = properties.getProviders();
    if (providers == null) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "Provider 配置未初始化");
    }
    return providers;
  }

  ProviderConfig getLegacyProviderConfigOrThrow(String id) {
    ProviderConfig config = getLegacyProvidersOrThrow().get(id);
    if (config == null) {
      throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
          "Provider '" + id + "' 不存在");
    }
    return config;
  }

  private void createProviderLegacy(CreateProviderRequest request) {
    Map<String, ProviderConfig> providers = getLegacyProvidersOrThrow();
    if (providers.containsKey(request.id())) {
      throw new BusinessException(ErrorCode.PROVIDER_ALREADY_EXISTS,
          "Provider '" + request.id() + "' 已存在");
    }

    ProviderConfig config = new ProviderConfig();
    config.setBaseUrl(request.baseUrl());
    config.setApiKey(request.apiKey());
    config.setModel(request.model());
    config.setEmbeddingModel(request.embeddingModel());
    config.setEmbeddingDimensions(request.embeddingDimensions());
    config.setSupportsEmbedding(request.supportsEmbedding());
    String rerankModel = trimOrNull(request.rerankModel());
    String rerankWorkspaceId = trimOrNull(request.rerankWorkspaceId());
    boolean supportsRerank = Boolean.TRUE.equals(request.supportsRerank());
    validateRerankConfig(request.id(), supportsRerank, rerankModel, rerankWorkspaceId);
    config.setSupportsRerank(supportsRerank);
    config.setRerankModel(supportsRerank ? rerankModel : null);
    config.setRerankWorkspaceId(supportsRerank ? rerankWorkspaceId : null);
    config.setTemperature(request.temperature());
    providers.put(request.id(), config);

    String envKey = toEnvKey(request.id());
    writeProviderToYaml(request.id(), config, envKey);
    writeEnvValue(envKey, request.apiKey());
    registry.reload();
  }

  private void updateProviderLegacy(String id, UpdateProviderRequest request) {
    ProviderConfig config = getLegacyProviderConfigOrThrow(id);
    String trimmedBaseUrl = trimOrNull(request.baseUrl());
    if (request.baseUrl() != null && trimmedBaseUrl == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空字符串");
    }
    String trimmedModel = trimOrNull(request.model());
    if (request.model() != null && trimmedModel == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空字符串");
    }
    String trimmedApiKey = trimOrNull(request.apiKey());
    if (request.apiKey() != null && trimmedApiKey == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串");
    }

    if (trimmedBaseUrl != null) config.setBaseUrl(trimmedBaseUrl);
    if (trimmedModel != null) config.setModel(trimmedModel);
    if (request.embeddingModel() != null) {
      config.setEmbeddingModel(trimOrNull(request.embeddingModel()));
    }
    if (request.embeddingDimensions() != null) {
      config.setEmbeddingDimensions(resolveEmbeddingDimensions(request.embeddingDimensions()));
    }
    if (request.supportsEmbedding() != null) {
      config.setSupportsEmbedding(request.supportsEmbedding());
    }
    boolean targetSupportsRerank = request.supportsRerank() != null
        ? request.supportsRerank() : Boolean.TRUE.equals(config.getSupportsRerank());
    boolean disablingRerank = Boolean.FALSE.equals(request.supportsRerank());
    String targetRerankModel = request.rerankModel() != null
        ? trimOrNull(request.rerankModel())
        : disablingRerank ? null : config.getRerankModel();
    String targetRerankWorkspaceId = request.rerankWorkspaceId() != null
        ? trimOrNull(request.rerankWorkspaceId())
        : disablingRerank ? null : config.getRerankWorkspaceId();
    validateRerankConfig(
        id, targetSupportsRerank, targetRerankModel, targetRerankWorkspaceId);
    config.setSupportsRerank(targetSupportsRerank);
    config.setRerankModel(targetSupportsRerank ? targetRerankModel : null);
    config.setRerankWorkspaceId(targetSupportsRerank ? targetRerankWorkspaceId : null);
    if (request.temperature() != null) {
      config.setTemperature(request.temperature());
    }
    if (trimmedApiKey != null) {
      config.setApiKey(trimmedApiKey);
      updateEnvValue(toEnvKey(id), trimmedApiKey);
    }

    writeProviderToYaml(id, config, toEnvKey(id));
    registry.reload();
  }

  private void deleteProviderLegacy(String id) {
    if (id.equals(properties.getDefaultProvider())) {
      throw new BusinessException(ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
          "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider");
    }
    getLegacyProviderConfigOrThrow(id);
    getLegacyProvidersOrThrow().remove(id);
    String envKey = toEnvKey(id);
    removeProviderFromYaml(id);
    removeFromEnv(envKey);
    registry.reload();
  }

  private ProviderRuntimeConfig toRuntimeConfig(ProviderConfig config) {
    return new ProviderRuntimeConfig(
        config.getBaseUrl(),
        config.getApiKey(),
        config.getModel(),
        config.getEmbeddingModel(),
        resolveEmbeddingDimensions(config.getEmbeddingDimensions()),
        Boolean.TRUE.equals(config.getSupportsEmbedding()) || trimOrNull(config.getEmbeddingModel()) != null,
        config.getTemperature()
    );
  }

  LlmProviderEntity getProviderEntityOrThrow(String id) {
    return providerRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
            "Provider '" + id + "' 不存在"));
  }

  private LlmGlobalSettingEntity getGlobalSettingOrThrow() {
    return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
            "默认 Provider 配置未初始化"));
  }

  private ProviderRuntimeConfig getProviderRuntimeConfigOrThrow(String id) {
    LlmProviderEntity provider = getProviderEntityOrThrow(id);
    return new ProviderRuntimeConfig(
        provider.getBaseUrl(),
        decryptApiKey(provider),
        provider.getModel(),
        provider.getEmbeddingModel(),
        resolveEmbeddingDimensions(provider.getEmbeddingDimensions()),
        provider.isSupportsEmbedding(),
        provider.getTemperature()
    );
  }

  private String decryptApiKey(LlmProviderEntity provider) {
    return encryptionService.decrypt(provider.getApiKeyNonce(), provider.getApiKeyCiphertext());
  }

  String maskApiKey(String apiKey) {
    if (apiKey == null || apiKey.length() <= 6) {
      return "***";
    }
    return apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length() - 3);
  }

  private List<String> buildConnectivityTestUrls(String baseUrl) {
    return connectivityTester.buildUrls(baseUrl);
  }

  private Map<String, Object> buildConnectivityTestRequestBody(String model) {
    return connectivityTester.buildRequestBody(model);
  }

  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private String requireNonBlank(String value, String fieldName) {
    String normalized = trimOrNull(value);
    if (normalized == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, fieldName + " 不能为空");
    }
    return normalized;
  }

  private void validateEmbeddingConfig(
      String providerId,
      boolean supportsEmbedding,
      String embeddingModel,
      Integer embeddingDimensions) {
    String normalizedModel = trimOrNull(embeddingModel);
    if (!supportsEmbedding) {
      return;
    }
    if (normalizedModel == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "支持 Embedding 的 Provider 必须填写 embeddingModel");
    }
//    if (looksLikeChatModel(normalizedModel)) {
//      String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
//      String suffix = recommendation != null
//          ? "，推荐填写 " + recommendation
//          : "，请填写该厂商真实的 Embedding 模型名";
//      throw new BusinessException(ErrorCode.BAD_REQUEST,
//          "Embedding Model 不能填写聊天模型 '" + normalizedModel + "'" + suffix);
//    }
    if (embeddingDimensions == null || embeddingDimensions <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "向量维度必须为正整数");
    }
  }

  private void validateRerankConfig(
      String providerId,
      boolean supportsRerank,
      String rerankModel,
      String rerankWorkspaceId) {
    String normalizedModel = trimOrNull(rerankModel);
    String normalizedWorkspaceId = trimOrNull(rerankWorkspaceId);
    boolean hasRerankConfig = supportsRerank
        || normalizedModel != null
        || normalizedWorkspaceId != null;
    if (!DASHSCOPE_PROVIDER_ID.equalsIgnoreCase(providerId) && hasRerankConfig) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "当前仅 DashScope Provider 支持配置 Rerank");
    }
    if (!supportsRerank) {
      if (normalizedModel != null || normalizedWorkspaceId != null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "配置 Rerank 模型或 Workspace ID 前必须启用 Rerank");
      }
      return;
    }
    if (normalizedModel == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "支持 Rerank 的 DashScope Provider 必须填写 rerankModel");
    }
    if (normalizedWorkspaceId == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "支持 Rerank 的 DashScope Provider 必须填写 rerankWorkspaceId");
    }
    if (normalizedModel.length() > MAX_RERANK_CONFIG_LENGTH) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "rerankModel 长度不能超过 " + MAX_RERANK_CONFIG_LENGTH);
    }
    if (normalizedWorkspaceId.length() > MAX_RERANK_CONFIG_LENGTH) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "rerankWorkspaceId 长度不能超过 " + MAX_RERANK_CONFIG_LENGTH);
    }
  }

  private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    return properties.getEmbeddingDimensions();
  }

  private boolean looksLikeChatModel(String model) {
    String lower = model.toLowerCase();
    return lower.startsWith("glm-")
        || lower.startsWith("deepseek")
        || lower.startsWith("kimi")
        || lower.startsWith("moonshot")
        || lower.startsWith("qwen")
        || lower.startsWith("ernie");
  }

  private String toEnvKey(String providerId) {
    return "PROVIDER_" + providerId.toUpperCase().replace("-", "_") + "_API_KEY";
  }

  // ===== Provider test logic (called under read lock) =====

  private ProviderTestResult doTestProvider(ProviderRuntimeConfig config, String id) {
    return connectivityTester.test(id, config.baseUrl(), config.apiKey(), config.model());
  }

  // ===== YAML text editing (preserves comments & formatting) =====

  private void writeProviderToYaml(String id, ProviderConfig config, String envKey) {
    configFileService.writeProviderToYaml(id, config, envKey);
  }

  private void removeProviderFromYaml(String id) {
    configFileService.removeProviderFromYaml(id);
  }

  private void writeDefaultProviderToYaml(String defaultProvider) {
    configFileService.writeDefaultProviderToYaml(defaultProvider);
  }

  private void writeEnvValue(String key, String value) {
    configFileService.writeEnvValue(key, value);
  }

  private void updateEnvValue(String key, String value) {
    configFileService.updateEnvValue(key, value);
  }

  private void removeFromEnv(String key) {
    configFileService.removeFromEnv(key);
  }

  private record ProviderRuntimeConfig(
      String baseUrl,
      String apiKey,
      String model,
      String embeddingModel,
      Integer embeddingDimensions,
      boolean supportsEmbedding,
      Double temperature
  ) {
  }
}
