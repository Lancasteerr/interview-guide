package interview.guide.common.ai;

import com.openai.client.OpenAIClient;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.RerankProperties;
import interview.guide.common.ai.rerank.RerankResult;
import interview.guide.common.ai.rerank.RerankExecutionMode;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing and caching LLM providers.
 * Supports dynamic creation of ChatClient based on provider configurations.
 */
@Component
@Slf4j
public class LlmProviderRegistry {

    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
    private final LlmProviderResolver providerResolver;
    private final LlmProviderRerankService rerankService;
    private final ObservationRegistry observationRegistry;

    private final LlmProviderChatClientService chatClientService;
    @Autowired
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService,
            RerankProperties rerankProperties,
            @Autowired(required = false) ToolCallingManager toolCallingManager,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) @Qualifier("interviewSkillsToolCallback") ToolCallback interviewSkillsToolCallback) {
        this.providerResolver = new LlmProviderResolver(
            properties, providerRepository, globalSettingRepository, encryptionService);
        this.observationRegistry = observationRegistry;
        this.chatClientService = new LlmProviderChatClientService(
            properties, providerResolver, toolCallingManager, observationRegistry,
            interviewSkillsToolCallback);
        this.rerankService = new LlmProviderRerankService(providerResolver, rerankProperties);
    }

    public LlmProviderRegistry(
            LlmProviderProperties properties,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry,
            ToolCallback interviewSkillsToolCallback) {
        this(properties, null, null, null, new RerankProperties(), toolCallingManager,
            observationRegistry, interviewSkillsToolCallback);
    }

    /**
     * Get a ChatClient for the specified provider ID.
     * If the client is not in the cache, it will be created based on the provider's configuration.
     *
     * @param providerId The ID of the provider (e.g., "dashscope", "lmstudio")
     * @return A ChatClient instance
     * @throws IllegalArgumentException if the providerId is unknown
     */
    public ChatClient getChatClient(String providerId) {
        return chatClientService.getChatClient(providerId);
    }

    /**
     * Get the default ChatClient based on app.ai.default-provider.
     *
     * @return The default ChatClient instance
     */
    public ChatClient getDefaultChatClient() {
        return getChatClient(providerResolver.resolveDefaultChatProviderId());
    }

    /**
     * 获取默认 provider 的不带 SkillsTool 的 ChatClient，用于纯粹的摘要 / 结构化文本场景。
     * 与 {@link #getDefaultChatClient()} 的区别在于不挂 Skill 工具与记忆 Advisor，避免无关上下文干扰。
     */
    public ChatClient getPlainChatClient() {
        return getPlainChatClient(providerResolver.resolveDefaultChatProviderId());
    }

    /**
     * Get a ChatClient for the specified provider, falling back to the default if null, blank, or
     * the legacy "default" alias.
     */
    public ChatClient getChatClientOrDefault(String providerId) {
        return getChatClient(providerResolver.resolveProviderId(providerId));
    }

    /**
     * 获取不带 SkillsTool 的 ChatClient，用于结构化输出场景（出题、简历评分等）。
     * 这些场景要求模型一次性返回可解析 JSON，不应混入工具调用消息。
     */
    public ChatClient getPlainChatClient(String providerId) {
        String id = providerResolver.resolveProviderId(providerId);
        return chatClientService.getPlainChatClient(id);
    }

    /**
     * 获取语音面试专用 ChatClient：SkillsTool + ToolCallAdvisor（流式）。
     * 不加 Memory Advisor（语音面试手动管理对话历史）。
     */
    public ChatClient getVoiceChatClient(String providerId) {
        String id = providerResolver.resolveProviderId(providerId);
        return chatClientService.getVoiceChatClient(id);
    }

    /**
     * 清空缓存，重新加载所有 provider。
     */
    public void reload() {
        int size = chatClientService.cacheSize() + embeddingModelCache.size()
            + rerankService.cacheSize();
        chatClientService.clearCache();
        embeddingModelCache.clear();
        rerankService.clearCache();
        log.info("[LlmProviderRegistry] Cache cleared ({} entries). Next access will re-create clients.", size);
    }

    public EmbeddingModel getEmbeddingModel(String providerId) {
        return embeddingModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new embedding model for provider: {}", id);
            return createEmbeddingModel(id);
        });
    }

    public EmbeddingModel getDefaultEmbeddingModel() {
        return getEmbeddingModel(providerResolver.resolveDefaultEmbeddingProviderId());
    }

    /**
     * 使用固定的 DashScope Provider 对召回文档重排序。
     * 全局开关、Provider 能力和模型支持范围都在此处统一判定。
     */
    public RerankResult rerankDocuments(String query, List<Document> candidates) {
        return rerankDocuments(query, candidates, RerankExecutionMode.CONFIGURED);
    }

    /**
     * Rerank with an explicit invocation mode. Evaluation uses this overload
     * to compare vector-only and rerank arms without changing the singleton
     * configuration object shared by the application context.
     */
    public RerankResult rerankDocuments(String query, List<Document> candidates,
                                        RerankExecutionMode mode) {
        return rerankService.rerankDocuments(query, candidates, mode);
    }

    private EmbeddingModel createEmbeddingModel(String providerId) {
        LlmProviderResolver.ProviderSnapshot config = providerResolver.loadProviderOrThrow(providerId);
        if (!config.supportsEmbedding() || providerResolver.isBlank(config.embeddingModel())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 未配置可用的 Embedding 模型，无法执行知识库向量化");
        }
//        if (looksLikeChatModel(config.embeddingModel())) {
//            String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
//            String suffix = recommendation != null
//                ? "，推荐填写 " + recommendation
//                : "，请填写该厂商真实的 Embedding 模型名";
//            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
//                "Provider '" + providerId + "' 的 Embedding Model 配成了聊天模型 '"
//                    + config.embeddingModel() + "'" + suffix);
//        }
        log.info("[LlmProviderRegistry] Building EmbeddingModel - Provider: {}, BaseUrl: {}, Model: {}",
            providerId, config.baseUrl(), config.embeddingModel());

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
            .model(config.embeddingModel())
            .dimensions(providerResolver.resolveEmbeddingDimensions(config.embeddingDimensions()))
            .build();

        return OpenAiEmbeddingModel.builder()
            .openAiClient(openAiClient)
            .metadataMode(MetadataMode.EMBED)
            .options(options)
            .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
            .build();
    }


}
