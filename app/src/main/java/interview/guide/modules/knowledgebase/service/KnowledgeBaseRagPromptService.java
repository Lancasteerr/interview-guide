package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.knowledgebase.config.KnowledgeBaseQueryProperties;
import interview.guide.modules.knowledgebase.metrics.RagMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责 RAG 查询的提示词、问题改写和检索参数规划。
 *
 * <p>该类不执行向量检索或模型回答，只把原始查询转换为稳定的查询计划，
 * 便于查询门面与具体处理流程解耦。</p>
 */
final class KnowledgeBaseRagPromptService {

  private static final int MAX_REWRITE_HISTORY_CHAR = 200;
  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseQueryService.class);

  private final LlmProviderRegistry llmProviderRegistry;
  private final RagMetrics ragMetrics;
  private final PromptTemplate systemPromptTemplate;
  private final PromptTemplate userPromptTemplate;
  private final PromptTemplate rewritePromptTemplate;
  private final boolean rewriteEnabled;
  private final int shortQueryLength;
  private final int topkShort;
  private final int topkMedium;
  private final int topkLong;
  private final double minScoreShort;
  private final double minScoreDefault;

  KnowledgeBaseRagPromptService(
      LlmProviderRegistry llmProviderRegistry,
      RagMetrics ragMetrics,
      KnowledgeBaseQueryProperties queryProperties,
      ResourceLoader resourceLoader) throws IOException {
    this.llmProviderRegistry = llmProviderRegistry;
    this.ragMetrics = ragMetrics;
    this.systemPromptTemplate = new PromptTemplate(
        resourceLoader.getResource(queryProperties.getSystemPromptPath())
            .getContentAsString(StandardCharsets.UTF_8));
    this.userPromptTemplate = new PromptTemplate(
        resourceLoader.getResource(queryProperties.getUserPromptPath())
            .getContentAsString(StandardCharsets.UTF_8));
    this.rewritePromptTemplate = new PromptTemplate(
        resourceLoader.getResource(queryProperties.getRewritePromptPath())
            .getContentAsString(StandardCharsets.UTF_8));
    this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
    this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
    this.topkShort = queryProperties.getSearch().getTopkShort();
    this.topkMedium = queryProperties.getSearch().getTopkMedium();
    this.topkLong = queryProperties.getSearch().getTopkLong();
    this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
    this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
  }

  String normalizeQuestion(String question) {
    return question == null ? "" : question.trim();
  }

  KnowledgeBaseRagQueryPlan buildQueryPlan(String originalQuestion, List<Message> history) {
    String normalizedQuestion = normalizeQuestion(originalQuestion);
    long rewriteStart = System.nanoTime();
    String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);
    long rewriteDurationMs = (System.nanoTime() - rewriteStart) / 1_000_000;

    List<String> candidateQueries = List.of(rewrittenQuestion, normalizedQuestion).stream()
        .distinct()
        .toList();
    return new KnowledgeBaseRagQueryPlan(
        normalizedQuestion,
        candidateQueries,
        resolveSearchParams(normalizedQuestion),
        rewriteDurationMs);
  }

  KnowledgeBaseRagSearchParams defaultSearchParams() {
    return new KnowledgeBaseRagSearchParams(topkLong, minScoreDefault);
  }

  String buildSystemPrompt() {
    return systemPromptTemplate.render()
        + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
  }

  String buildUserPrompt(String context, String question) {
    Map<String, Object> variables = new HashMap<>();
    variables.put("context", context);
    variables.put("question", question);
    return userPromptTemplate.render(variables);
  }

  private String rewriteQuestion(String question, List<Message> history) {
    if (!rewriteEnabled) {
      ragMetrics.recordRewriteFallback("disabled");
      return question;
    }
    if (question.isBlank()) {
      ragMetrics.recordRewriteFallback("blank");
      return question;
    }
    try {
      Map<String, Object> variables = new HashMap<>();
      variables.put("question", question);
      variables.put("history", formatHistoryForRewrite(history));
      String rewritePrompt = rewritePromptTemplate.render(variables);
      String rewritten = llmProviderRegistry.getPlainChatClient().prompt()
          .user(rewritePrompt)
          .call()
          .content();
      if (rewritten == null || rewritten.isBlank()) {
        return question;
      }
      String normalized = rewritten.trim();
      if (normalized.equals(question)) {
        ragMetrics.recordRewriteFallback("unchanged");
      }
      LOG.info("Query rewrite 完成: originLength={}, rewrittenLength={}, changed={}, historySize={}",
          question.length(), normalized.length(), !normalized.equals(question),
          history == null ? 0 : history.size());
      return normalized;
    } catch (Exception e) {
      ragMetrics.recordRewriteFallback("error");
      LOG.warn("Query rewrite 失败，使用原问题继续检索: {}",
          ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
      return question;
    }
  }

  private String formatHistoryForRewrite(List<Message> history) {
    if (history == null || history.isEmpty()) {
      return "";
    }
    StringBuilder summary = new StringBuilder();
    for (Message message : history) {
      if (message instanceof UserMessage) {
        summary.append("用户: ").append(message.getText()).append("\n");
      } else if (message instanceof AssistantMessage) {
        String text = message.getText();
        if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
          text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
        }
        summary.append("助手: ").append(text).append("\n");
      }
    }
    return summary.toString().trim();
  }

  private KnowledgeBaseRagSearchParams resolveSearchParams(String question) {
    int compactLength = question.replaceAll("\\s+", "").length();
    if (compactLength <= shortQueryLength) {
      return new KnowledgeBaseRagSearchParams(topkShort, minScoreShort);
    }
    if (compactLength <= 12) {
      return new KnowledgeBaseRagSearchParams(topkMedium, minScoreDefault);
    }
    return new KnowledgeBaseRagSearchParams(topkLong, minScoreDefault);
  }
}
