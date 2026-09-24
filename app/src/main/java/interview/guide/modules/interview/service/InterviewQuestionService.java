package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.interview.config.InterviewQuestionProperties;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.dto.InterviewQuestionDTO;
import interview.guide.modules.interview.service.InterviewSkillService.CategoryDTO;
import interview.guide.modules.interview.service.InterviewSkillService.SkillDTO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 面试问题生成门面：编排简历题、方向题并行生成和结果合并。
 */
@Slf4j
@Service
public class InterviewQuestionService {

  private static final double RESUME_QUESTION_RATIO = 0.6;

  private final LlmProviderRegistry llmProviderRegistry;
  private final ExecutorService questionExecutor;
  private final InterviewQuestionResultAssembler resultAssembler;
  private final InterviewQuestionGenerationService generationService;

  record QuestionListDTO(List<QuestionDTO> questions) {}

  record QuestionDTO(
      String question,
      String type,
      String category,
      String topicSummary,
      List<String> followUps) {}

  public InterviewQuestionService(
      StructuredOutputInvoker structuredOutputInvoker,
      InterviewSkillService skillService,
      InterviewQuestionProperties properties,
      ResourceLoader resourceLoader,
      LlmProviderRegistry llmProviderRegistry,
      PromptSanitizer promptSanitizer) throws IOException {
    this.llmProviderRegistry = llmProviderRegistry;
    this.questionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    int followUpCount = Math.max(0, Math.min(properties.getFollowUpCount(), 2));
    this.resultAssembler = new InterviewQuestionResultAssembler(followUpCount);
    this.generationService = new InterviewQuestionGenerationService(
        structuredOutputInvoker,
        skillService,
        properties,
        resourceLoader,
        promptSanitizer,
        followUpCount,
        resultAssembler
    );
  }

  @PreDestroy
  void destroy() {
    questionExecutor.shutdownNow();
  }

  public List<InterviewQuestionDTO> generateQuestionsBySkill(
      String llmProvider,
      String skillId,
      String difficulty,
      String resumeText,
      int questionCount,
      List<HistoricalQuestion> historicalQuestions,
      List<CategoryDTO> customCategories,
      String jdText) {
    SkillDTO skill = generationService.resolveSkill(skillId, customCategories, jdText);
    String difficultyDesc = generationService.resolveDifficulty(difficulty);
    ChatClient questionChatClient = llmProviderRegistry.getPlainChatClient(llmProvider);
    String historicalSection = resultAssembler.buildHistoricalSection(historicalQuestions);

    boolean hasResume = resumeText != null && !resumeText.isBlank();
    if (!hasResume) {
      return generationService.generateDirectionOnly(
          questionChatClient, skill, difficultyDesc, questionCount, historicalSection);
    }

    int resumeCount = Math.max(1, (int) Math.round(questionCount * RESUME_QUESTION_RATIO));
    int directionCount = questionCount - resumeCount;
    log.info("并行出题: skill={}, total={}, resumeCount={}, directionCount={}",
        skillId, questionCount, resumeCount, directionCount);

    CompletableFuture<List<InterviewQuestionDTO>> resumeFuture = CompletableFuture.supplyAsync(
        () -> generationService.generateResumeQuestions(
            questionChatClient, resumeText, resumeCount, skill, difficultyDesc, historicalSection),
        questionExecutor);
    CompletableFuture<List<InterviewQuestionDTO>> directionFuture = CompletableFuture.supplyAsync(
        () -> generationService.generateDirectionOnly(
            questionChatClient, skill, difficultyDesc, directionCount, historicalSection),
        questionExecutor);

    List<InterviewQuestionDTO> resumeQuestions;
    List<InterviewQuestionDTO> directionQuestions;
    try {
      resumeQuestions = resumeFuture.join();
    } catch (CompletionException e) {
      log.error("简历题生成失败，降级为全方向题", e.getCause());
      directionFuture.cancel(true);
      return generationService.generateDirectionOnly(
          questionChatClient, skill, difficultyDesc, questionCount, historicalSection);
    }

    try {
      directionQuestions = directionFuture.join();
    } catch (CompletionException e) {
      log.error("方向题生成失败，降级为全简历题", e.getCause());
      if (resumeQuestions.isEmpty()) {
        return resultAssembler.fallback(skill, questionCount);
      }
      return resumeQuestions;
    }

    if (resumeQuestions.isEmpty() && directionQuestions.isEmpty()) {
      log.warn("简历题和方向题均为空，回退到默认问题");
      return resultAssembler.fallback(skill, questionCount);
    }

    List<InterviewQuestionDTO> merged = resultAssembler.merge(resumeQuestions, directionQuestions);
    log.info("并行出题成功: 简历题={}, 方向题={}, 合计={}",
        resumeQuestions.size(), directionQuestions.size(), merged.size());
    return merged;
  }
}
