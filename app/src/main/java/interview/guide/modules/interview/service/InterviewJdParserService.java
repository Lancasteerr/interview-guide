package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.service.InterviewSkillService.CategoryDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 负责职位描述解析和分类结果的结构化输出。
 */
@Slf4j
final class InterviewJdParserService {

  private static final int MIN_JD_LENGTH = 50;
  private static final String JD_PARSE_SYSTEM_PROMPT_PATH = "classpath:prompts/interview/jd-parse-system.st";

  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final BeanOutputConverter<CategoryListDTO> outputConverter;
  private final PromptTemplate systemPromptTemplate;
  private final PromptSanitizer promptSanitizer;

  InterviewJdParserService(
      LlmProviderRegistry llmProviderRegistry,
      StructuredOutputInvoker structuredOutputInvoker,
      ResourceLoader resourceLoader,
      PromptSanitizer promptSanitizer) throws IOException {
    this.llmProviderRegistry = llmProviderRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.promptSanitizer = promptSanitizer;
    this.outputConverter = new BeanOutputConverter<>(CategoryListDTO.class) {};
    Resource resource = resourceLoader.getResource(JD_PARSE_SYSTEM_PROMPT_PATH);
    this.systemPromptTemplate = new PromptTemplate(resource.getContentAsString(StandardCharsets.UTF_8));
  }

  List<CategoryDTO> parse(String jdText, String referenceFileList) {
    if (jdText == null || jdText.length() < MIN_JD_LENGTH) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "JD 内容太少（至少 " + MIN_JD_LENGTH + " 字），请补充后重试");
    }

    log.info("开始解析 JD，长度: {}", jdText.length());
    ChatClient chatClient = llmProviderRegistry.getDefaultChatClient();
    String systemPrompt = systemPromptTemplate.render(Map.of(
        "referenceFileList", referenceFileList)) + "\n\n" + outputConverter.getFormat();
    String userPrompt = PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n"
        + "职位描述：\n"
        + promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(jdText));

    try {
      CategoryListDTO result = structuredOutputInvoker.invoke(
          chatClient, systemPrompt, userPrompt, outputConverter,
          ErrorCode.AI_SERVICE_ERROR, "JD 解析失败：", "JD 解析", log);
      if (result == null || result.categories() == null || result.categories().isEmpty()) {
        throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析结果为空，请重试");
      }
      long refMatched = result.categories().stream()
          .filter(category -> category.ref() != null && !category.ref().isBlank())
          .count();
      log.info("JD 解析完成: {} 个方向, {} 个匹配到参考文件", result.categories().size(), refMatched);
      return result.categories();
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("JD 解析失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析失败，请重试或选择预设主题");
    }
  }

  private record CategoryListDTO(List<CategoryDTO> categories) {}
}
