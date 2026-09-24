package interview.guide.common.ai;

import com.openai.client.OpenAIClient;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.AdvisorConfig;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM ChatClient/ChatModel 的创建、Advisor 组装和缓存。
 */
@Slf4j
final class LlmProviderChatClientService {

  private final LlmProviderProperties properties;
  private final LlmProviderResolver providerResolver;
  private final ToolCallingManager toolCallingManager;
  private final ObservationRegistry observationRegistry;
  private final ToolCallback interviewSkillsToolCallback;
  private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();
  private final Map<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();

  LlmProviderChatClientService(
      LlmProviderProperties properties,
      LlmProviderResolver providerResolver,
      ToolCallingManager toolCallingManager,
      ObservationRegistry observationRegistry,
      ToolCallback interviewSkillsToolCallback) {
    this.properties = properties;
    this.providerResolver = providerResolver;
    this.toolCallingManager = toolCallingManager;
    this.observationRegistry = observationRegistry;
    this.interviewSkillsToolCallback = interviewSkillsToolCallback;
  }

  ChatClient getChatClient(String providerId) {
    return clientCache.computeIfAbsent(providerId, id -> {
      log.info("[LlmProviderRegistry] Creating new client for provider: {}", id);
      return createChatClient(id);
    });
  }

  ChatClient getPlainChatClient(String providerId) {
    return clientCache.computeIfAbsent(providerId + ":plain",
        key -> createPlainChatClient(providerId));
  }

  ChatClient getVoiceChatClient(String providerId) {
    return clientCache.computeIfAbsent(providerId + ":voice",
        key -> createVoiceChatClient(providerId));
  }

  int cacheSize() {
    return clientCache.size() + chatModelCache.size();
  }

  void clearCache() {
    clientCache.clear();
    chatModelCache.clear();
  }

  private ChatClient createChatClient(String providerId) {
    OpenAiChatModel chatModel = getChatModel(providerId);
    ChatClient.Builder builder = ChatClient.builder(chatModel);
    if (interviewSkillsToolCallback != null) {
      builder.defaultTools(interviewSkillsToolCallback);
    }
    List<Advisor> advisors = buildDefaultAdvisors(providerId);
    if (!advisors.isEmpty()) {
      builder.defaultAdvisors(advisors);
      log.info("[LlmProviderRegistry] Applied {} advisors for provider {}", advisors.size(), providerId);
    }
    return builder.build();
  }

  private ChatClient createPlainChatClient(String providerId) {
    OpenAiChatModel chatModel = getChatModel(providerId);
    ChatClient.Builder builder = ChatClient.builder(chatModel);
    buildSafeGuardAdvisor().ifPresent(advisor -> builder.defaultAdvisors(List.of(advisor)));
    log.info("[LlmProviderRegistry] Created plain ChatClient (no tools) for {}", providerId);
    return builder.build();
  }

  private ChatClient createVoiceChatClient(String providerId) {
    OpenAiChatModel chatModel = getChatModel(providerId);
    ChatClient.Builder builder = ChatClient.builder(chatModel);
    if (interviewSkillsToolCallback != null) {
      builder.defaultTools(interviewSkillsToolCallback);
    }
    List<Advisor> advisors = new ArrayList<>();
    if (toolCallingManager != null) {
      advisors.add(buildToolCallAdvisor(true));
    }
    buildSafeGuardAdvisor().ifPresent(advisors::add);
    if (!advisors.isEmpty()) {
      builder.defaultAdvisors(advisors);
    }
    log.info("[LlmProviderRegistry] Created voice ChatClient (SkillsTool + streaming ToolCall) for {}",
        providerId);
    return builder.build();
  }

  private OpenAiChatModel getChatModel(String providerId) {
    return chatModelCache.computeIfAbsent(providerId, id -> {
      log.info("[LlmProviderRegistry] Creating new ChatModel for provider: {}", id);
      return buildChatModel(id);
    });
  }

  private OpenAiChatModel buildChatModel(String providerId) {
    LlmProviderResolver.ProviderSnapshot config = providerResolver.loadProviderOrThrow(providerId);
    log.info("[LlmProviderRegistry] Building ChatModel - Provider: {}, BaseUrl: {}, Model: {}",
        providerId, config.baseUrl(), config.model());
    OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());
    OpenAiChatOptions options = OpenAiChatOptions.builder()
        .model(config.model())
        .temperature(config.temperature() != null ? config.temperature() : 0.2)
        .build();
    return OpenAiChatModel.builder()
        .openAiClient(openAiClient)
        .openAiClientAsync(openAiClient.async())
        .options(options)
        .observationRegistry(observationRegistry != null
            ? observationRegistry : ObservationRegistry.NOOP)
        .build();
  }

  private List<Advisor> buildDefaultAdvisors(String providerId) {
    AdvisorConfig config = properties.getAdvisors();
    if (config == null || !config.isEnabled()) {
      return List.of();
    }
    List<Advisor> advisors = new ArrayList<>();
    if (config.isToolCallEnabled()) {
      if (toolCallingManager != null) {
        advisors.add(buildToolCallAdvisor(config.isToolCallConversationHistoryEnabled()));
      } else {
        log.warn("[LlmProviderRegistry] ToolCallAdvisor skipped: ToolCallingManager unavailable, provider={}",
            providerId);
      }
    }
    if (config.isMessageChatMemoryEnabled()) {
      int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
      MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
          MessageWindowChatMemory.builder().maxMessages(maxMessages).build()).build();
      advisors.add(memoryAdvisor);
    }
    if (config.isSimpleLoggerEnabled()) {
      advisors.add(new SimpleLoggerAdvisor());
    }
    buildSafeGuardAdvisor().ifPresent(advisors::add);
    return advisors;
  }

  private ToolCallingAdvisor buildToolCallAdvisor(boolean conversationHistoryEnabled) {
    return ToolCallingAdvisor.builder()
        .toolCallingManager(toolCallingManager)
        .conversationHistoryEnabled(conversationHistoryEnabled)
        .build();
  }

  private Optional<SafeGuardAdvisor> buildSafeGuardAdvisor() {
    AdvisorConfig config = properties.getAdvisors();
    if (config == null || !config.isSafeguardEnabled()) {
      return Optional.empty();
    }
    SafeGuardAdvisor advisor = SafeGuardAdvisor.builder()
        .sensitiveWords(config.getSafeguardWords())
        .failureResponse("抱歉，我只能协助面试相关的任务。")
        .order(100)
        .build();
    return Optional.of(advisor);
  }
}
