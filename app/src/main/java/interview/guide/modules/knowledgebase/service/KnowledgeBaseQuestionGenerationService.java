package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.knowledgebase.entity.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.dto.KnowledgeBaseQuestionFollowUpDTO;
import interview.guide.modules.knowledgebase.model.QuestionGenerationConfig;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 知识库问题异步生成服务
 * 由 QuestionGenStreamConsumer 调用，负责实际的 LLM 生成和结果持久化。
 * LLM 调用不在事务内；删除旧问题和保存新问题在同一个最小事务中完成。
 */
@Slf4j
@Service
public class KnowledgeBaseQuestionGenerationService {

  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final PromptSanitizer promptSanitizer;
  private final QuestionGenerationStateService stateService;
  private final KnowledgeBaseQuestionContextService contextService;
  private final KnowledgeBaseQuestionEntityFactory entityFactory;

  @Value("classpath:prompts/knowledgebase/knowledgebase-question-generation-system.st")
  private Resource systemPromptResource;

  @Value("classpath:prompts/knowledgebase/knowledgebase-question-generation-user.st")
  private Resource userPromptResource;

  private final BeanOutputConverter<QuestionListDTO> outputConverter =
      new BeanOutputConverter<>(QuestionListDTO.class);

  record QuestionListDTO(List<QuestionDTO> questions) {}

  record QuestionDTO(
      String category,
      String type,
      String question,
      String topicSummary,
      String referenceAnswer,
      List<String> keyPoints,
      String scoringRubric,
      List<KnowledgeBaseQuestionFollowUpDTO> followUps
  ) {}

  public KnowledgeBaseQuestionGenerationService(
      KnowledgeBaseRepository knowledgeBaseRepository,
      KnowledgeBaseQuestionRepository questionRepository,
      KnowledgeBaseVectorService vectorService,
      LlmProviderRegistry llmProviderRegistry,
      StructuredOutputInvoker structuredOutputInvoker,
      PromptSanitizer promptSanitizer,
      QuestionGenerationStateService stateService,
      ObjectMapper objectMapper) {
    this.knowledgeBaseRepository = knowledgeBaseRepository;
    this.llmProviderRegistry = llmProviderRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.promptSanitizer = promptSanitizer;
    this.stateService = stateService;
    this.contextService = new KnowledgeBaseQuestionContextService(questionRepository, vectorService);
    this.entityFactory = new KnowledgeBaseQuestionEntityFactory(objectMapper);
  }

  /**
   * 执行问题生成（由 Consumer 调用，不在事务中）
   * 流程：校验 → 检索上下文 → 调用 LLM → 替换旧问题并保存新问题（小事务）
   */
  public void executeGeneration(
      Long kbId,
      String taskId,
      QuestionGenerationConfig config
  ) {
    KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
        .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

    // 再次确认任务ID匹配
    if (!taskId.equals(kb.getQuestionGenTaskId())) {
      log.info("任务ID不匹配，放弃生成: kbId={}, msgTaskId={}, currentTaskId={}",
          kbId, taskId, kb.getQuestionGenTaskId());
      return;
    }

    String normalizedDifficulty = normalizeDifficulty(config.difficulty());
    int normalizedFollowUp = Math.max(0, Math.min(config.followUpCount(), 5));
    int normalizedCategoryLimit = Math.max(1, Math.min(config.categoryLimit(), 5));

    // 1. 检索上下文（不在事务中）
    String context = contextService.buildGenerationContext(kb);

    // 2. 调用 LLM（不在事务中）
    ChatClient chatClient = llmProviderRegistry.getPlainChatClient(config.llmProvider());
    QuestionListDTO generated = callLlm(kb, chatClient, normalizedDifficulty,
        Math.max(1, config.questionCount()), normalizedFollowUp, normalizedCategoryLimit, context);

    // 3. 校验生成结果
    if (generated == null || generated.questions() == null || generated.questions().isEmpty()) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "知识库题库生成结果为空");
    }

    // 4. 构建实体列表（不在事务中）
    KnowledgeBaseQuestionEntityFactory.GenerationBatch batch =
        entityFactory.build(kb, normalizedDifficulty, context, normalizedFollowUp, generated);
    if (batch.questions().isEmpty()) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "知识库题库生成结果无有效题干");
    }

    // 5. 在一个小事务中校验当前任务、替换题目并更新完成状态
    boolean completed = stateService.replaceQuestionsAndComplete(
        kbId, taskId, batch.questions(), batch.skippedCount());
    if (!completed) {
      log.info("题目生成任务已被替换，丢弃旧结果: kbId={}, taskId={}", kbId, taskId);
      return;
    }

    log.info("知识库问题异步生成完成: kbId={}, taskId={}, count={}",
        kbId, taskId, batch.questions().size());
  }

  private QuestionListDTO callLlm(
      KnowledgeBaseEntity kb,
      ChatClient chatClient,
      String difficulty,
      int questionCount,
      int followUpCount,
      int categoryLimit,
      String context
  ) {
    try {
      String systemPrompt = loadTemplate(systemPromptResource).render()
          + "\n\n"
          + outputConverter.getFormat();
      String userPrompt = loadTemplate(userPromptResource)
          .render(Map.of(
              "knowledgeBaseName", promptSanitizer.sanitize(kb.getName()),
              "difficulty", difficulty,
              "questionCount", questionCount,
              "followUpCount", followUpCount,
              "categoryLimit", categoryLimit,
              "existingCategories", promptSanitizer.sanitize(
                  contextService.buildExistingCategorySection(kb.getId())),
              "existingQuestions", promptSanitizer.sanitize(
                  contextService.buildExistingQuestionSection(kb.getId(), difficulty)),
              "context", PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n"
                  + promptSanitizer.wrapWithDelimiters("knowledge-base",
                      promptSanitizer.sanitize(context))
          ));

      return structuredOutputInvoker.invoke(
          chatClient,
          systemPrompt,
          userPrompt,
          outputConverter,
          ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "知识库题库生成失败：",
          "知识库题库生成",
          log
      );
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("知识库题库生成LLM调用失败: kbId={}, error={}", kb.getId(),
          ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "知识库题库生成失败");
    }
  }

  private PromptTemplate loadTemplate(Resource resource) throws IOException {
    try (var input = resource.getInputStream()) {
      String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      return new PromptTemplate(content);
    }
  }

  private String normalizeDifficulty(String difficulty) {
    if (difficulty == null || difficulty.isBlank()) {
      return InterviewDefaults.DIFFICULTY;
    }
    return difficulty.trim();
  }

}
