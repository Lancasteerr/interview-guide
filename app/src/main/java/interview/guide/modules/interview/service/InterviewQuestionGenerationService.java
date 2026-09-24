package interview.guide.modules.interview.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.interview.config.InterviewQuestionProperties;
import interview.guide.modules.interview.dto.InterviewQuestionDTO;
import interview.guide.modules.interview.service.InterviewSkillService.CategoryDTO;
import interview.guide.modules.interview.service.InterviewSkillService.SkillDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责面试题 Prompt 构造、按来源生成和生成失败降级。
 */
final class InterviewQuestionGenerationService {

  private static final Logger log = LoggerFactory.getLogger(InterviewQuestionGenerationService.class);
  private static final int MAX_FOLLOW_UP_COUNT = 2;
  private static final Map<String, String> DIFFICULTY_DESCRIPTIONS = Map.of(
      "junior", "校招/0-1年经验。考察基础概念和简单应用。",
      "mid", "1-3年经验。考察原理理解和实战经验。",
      "senior", "3年+经验。考察架构设计和深度调优。"
  );
  private static final String GENERIC_MODE_SYSTEM_APPEND = """
      \n\n# 通用面试模式
      本次面试无候选人简历，请出该方向的标准面试题。
      - 禁止出现"你在简历中提到..."、"你在项目中..."等暗示存在简历的表述
      - 问题表述应与简历无关，直接考察该方向的技术能力
      """;

  private final PromptTemplate skillSystemPromptTemplate;
  private final PromptTemplate skillUserPromptTemplate;
  private final PromptTemplate resumeSystemPromptTemplate;
  private final PromptTemplate resumeUserPromptTemplate;
  private final BeanOutputConverter<InterviewQuestionService.QuestionListDTO> outputConverter;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final InterviewSkillService skillService;
  private final PromptSanitizer promptSanitizer;
  private final int followUpCount;
  private final InterviewQuestionResultAssembler resultAssembler;

  InterviewQuestionGenerationService(
      StructuredOutputInvoker structuredOutputInvoker,
      InterviewSkillService skillService,
      InterviewQuestionProperties properties,
      ResourceLoader resourceLoader,
      PromptSanitizer promptSanitizer,
      int followUpCount,
      InterviewQuestionResultAssembler resultAssembler) throws IOException {
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.skillService = skillService;
    this.promptSanitizer = promptSanitizer;
    this.followUpCount = followUpCount;
    this.resultAssembler = resultAssembler;
    this.skillSystemPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionSystemPromptPath());
    this.skillUserPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionUserPromptPath());
    this.resumeSystemPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionSystemPromptPath());
    this.resumeUserPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionUserPromptPath());
    this.outputConverter = new BeanOutputConverter<>(InterviewQuestionService.QuestionListDTO.class);
  }

  SkillDTO resolveSkill(String skillId, List<CategoryDTO> customCategories, String jdText) {
    if (InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId)
        && customCategories != null && !customCategories.isEmpty()) {
      return skillService.buildCustomSkill(customCategories, jdText != null ? jdText : "");
    }
    return skillService.getSkill(skillId);
  }

  String resolveDifficulty(String difficulty) {
    return DIFFICULTY_DESCRIPTIONS.getOrDefault(
        difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY,
        DIFFICULTY_DESCRIPTIONS.get(InterviewDefaults.DIFFICULTY));
  }

  List<InterviewQuestionDTO> generateResumeQuestions(
      ChatClient questionClient,
      String resumeText,
      int questionCount,
      SkillDTO skill,
      String difficultyDesc,
      String historicalSection) {
    try {
      Map<String, Object> variables = new HashMap<>();
      variables.put("questionCount", questionCount);
      variables.put("followUpCount", followUpCount);
      variables.put("skillName", skill.name());
      variables.put("skillDescription", skill.description() != null ? skill.description() : "");
      variables.put("difficultyDescription", difficultyDesc);
      variables.put("resumeText", resumeText);
      variables.put("historicalSection", historicalSection);

      String systemPrompt = resumeSystemPromptTemplate.render()
          + buildSkillPersonaSection(skill)
          + "\n\n" + outputConverter.getFormat();
      String userPrompt = resumeUserPromptTemplate.render(variables);
      InterviewQuestionService.QuestionListDTO dto = structuredOutputInvoker.invoke(
          questionClient, systemPrompt, userPrompt, outputConverter,
          ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "简历题生成失败：", "简历题", log);

      List<InterviewQuestionDTO> questions = resultAssembler.capToMainCount(
          resultAssembler.convert(dto), questionCount);
      log.info("简历题生成完成: 请求={}, 实际主问题={}",
          questionCount, questions.stream().filter(question -> !question.isFollowUp()).count());
      return questions;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("简历题生成异常: {}", ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
      throw e;
    }
  }

  List<InterviewQuestionDTO> generateDirectionOnly(
      ChatClient questionClient,
      SkillDTO skill,
      String difficultyDesc,
      int questionCount,
      String historicalSection) {
    Map<String, Integer> allocation = skillService.calculateAllocation(skill.categories(), questionCount);
    String allocationTable = skillService.buildAllocationDescription(allocation, skill.categories());
    log.info("方向题生成: skill={}, total={}, allocation={}", skill.id(), questionCount, allocation);

    try {
      Map<String, Object> variables = new HashMap<>();
      variables.put("questionCount", questionCount);
      variables.put("followUpCount", followUpCount);
      variables.put("difficultyDescription", difficultyDesc);
      variables.put("skillName", skill.name());
      variables.put("skillDescription", skill.description() != null ? skill.description() : "");
      variables.put("allocationTable", allocationTable);
      variables.put("historicalSection", historicalSection);
      variables.put("referenceSection", skillService.buildReferenceSection(skill, allocation));
      variables.put("jdSection", buildJdSection(skill.sourceJd()));

      String systemPrompt = skillSystemPromptTemplate.render()
          + buildSkillPersonaSection(skill)
          + GENERIC_MODE_SYSTEM_APPEND
          + outputConverter.getFormat();
      String userPrompt = skillUserPromptTemplate.render(variables);
      InterviewQuestionService.QuestionListDTO dto = structuredOutputInvoker.invoke(
          questionClient, systemPrompt, userPrompt, outputConverter,
          ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "方向题生成失败：", "方向题", log);

      List<InterviewQuestionDTO> questions = resultAssembler.convert(dto);
      if (questions.stream().noneMatch(question -> !question.isFollowUp())) {
        log.warn("方向题返回空题单，回退到默认问题");
        return resultAssembler.fallback(skill, questionCount);
      }
      questions = resultAssembler.capToMainCount(questions, questionCount);
      log.info("方向题生成完成: 请求={}, 实际主问题={}",
          questionCount, questions.stream().filter(question -> !question.isFollowUp()).count());
      return questions;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("方向题生成失败，回退到默认问题: {}",
          ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
      return resultAssembler.fallback(skill, questionCount);
    }
  }

  private static PromptTemplate loadTemplate(ResourceLoader loader, String location) throws IOException {
    return new PromptTemplate(loader.getResource(location).getContentAsString(StandardCharsets.UTF_8));
  }

  private String buildJdSection(String sourceJd) {
    if (sourceJd == null || sourceJd.isBlank()) {
      return "";
    }
    return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n"
        + "## 职位描述（JD）\n根据以下 JD 关键要求出题，确保题目与岗位实际需求相关：\n"
        + promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(sourceJd));
  }

  private String buildSkillPersonaSection(SkillDTO skill) {
    if (skill == null || skill.persona() == null || skill.persona().isBlank()) {
      return "";
    }
    return "\n\n# Skill Persona\n"
        + "以下内容来自当前面试方向的 SKILL.md，请作为面试官角色、风格与出题约束：\n"
        + promptSanitizer.wrapWithDelimiters("skill_persona", skill.persona());
  }
}
