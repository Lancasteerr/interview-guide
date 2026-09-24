package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.interview.config.InterviewQuestionProperties;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.dto.InterviewQuestionDTO;
import interview.guide.modules.interview.service.InterviewSkillService;
import interview.guide.modules.interview.service.InterviewSkillService.CategoryDTO;
import interview.guide.modules.interview.service.InterviewSkillService.SkillDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 面试问题生成服务
 * 无简历：单次 Skill 驱动出题
 * 有简历：并行调用（简历题 60% + 方向题 40%）
 */
@Service
public class InterviewQuestionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);

    private static final int MAX_FOLLOW_UP_COUNT = 2;
    private static final double RESUME_QUESTION_RATIO = 0.6;

    private static final String GENERIC_MODE_SYSTEM_APPEND = """
        \n\n# 通用面试模式
        本次面试无候选人简历，请出该方向的标准面试题。
        - 禁止出现"你在简历中提到..."、"你在项目中..."等暗示存在简历的表述
        - 问题表述应与简历无关，直接考察该方向的技术能力
        """;

    private static final Map<String, String> DIFFICULTY_DESCRIPTIONS = Map.of(
        "junior", "校招/0-1年经验。考察基础概念和简单应用。",
        "mid", "1-3年经验。考察原理理解和实战经验。",
        "senior", "3年+经验。考察架构设计和深度调优。"
    );

    private final PromptTemplate skillSystemPromptTemplate;
    private final PromptTemplate skillUserPromptTemplate;
    private final PromptTemplate resumeSystemPromptTemplate;
    private final PromptTemplate resumeUserPromptTemplate;
    private final BeanOutputConverter<QuestionListDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final InterviewSkillService skillService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptSanitizer promptSanitizer;
    private final ExecutorService questionExecutor;
    private final int followUpCount;
    private final InterviewQuestionResultAssembler resultAssembler;

    record QuestionListDTO(List<QuestionDTO> questions) {}

    record QuestionDTO(String question, String type, String category,
                               String topicSummary, List<String> followUps) {}

    public InterviewQuestionService(
            StructuredOutputInvoker structuredOutputInvoker,
            InterviewSkillService skillService,
            InterviewQuestionProperties properties,
            ResourceLoader resourceLoader,
            LlmProviderRegistry llmProviderRegistry,
            PromptSanitizer promptSanitizer) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.skillService = skillService;
        this.llmProviderRegistry = llmProviderRegistry;
        this.promptSanitizer = promptSanitizer;
        this.questionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.skillSystemPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionSystemPromptPath());
        this.skillUserPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionUserPromptPath());
        this.resumeSystemPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionSystemPromptPath());
        this.resumeUserPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionUserPromptPath());
        this.outputConverter = new BeanOutputConverter<>(QuestionListDTO.class);
        this.followUpCount = Math.max(0, Math.min(properties.getFollowUpCount(), MAX_FOLLOW_UP_COUNT));
        this.resultAssembler = new InterviewQuestionResultAssembler(followUpCount);
    }

    private static PromptTemplate loadTemplate(ResourceLoader loader, String location) throws IOException {
        return new PromptTemplate(loader.getResource(location).getContentAsString(StandardCharsets.UTF_8));
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

        SkillDTO skill = resolveSkill(skillId, customCategories, jdText);
        String difficultyDesc = resolveDifficulty(difficulty);
        ChatClient questionChatClient =
            llmProviderRegistry.getPlainChatClient(llmProvider);

        boolean hasResume = resumeText != null && !resumeText.isBlank();
        String historicalSection = resultAssembler.buildHistoricalSection(historicalQuestions);
        if (!hasResume) {
            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, questionCount,
                historicalSection);
        }

        int resumeCount = Math.max(1, (int) Math.round(questionCount * RESUME_QUESTION_RATIO));
        int directionCount = questionCount - resumeCount;

        log.info("并行出题: skill={}, total={}, resumeCount={}, directionCount={}",
            skillId, questionCount, resumeCount, directionCount);

        CompletableFuture<List<InterviewQuestionDTO>> resumeFuture = CompletableFuture.supplyAsync(
            () -> generateResumeQuestions(questionChatClient, resumeText, resumeCount, skill,
                difficultyDesc, historicalSection),
            questionExecutor);

        CompletableFuture<List<InterviewQuestionDTO>> directionFuture = CompletableFuture.supplyAsync(
            () -> generateDirectionOnly(questionChatClient, skill, difficultyDesc, directionCount,
                historicalSection),
            questionExecutor);

        List<InterviewQuestionDTO> resumeQuestions;
        List<InterviewQuestionDTO> directionQuestions;
        try {
            resumeQuestions = resumeFuture.join();
        } catch (CompletionException e) {
            log.error("简历题生成失败，降级为全方向题",
                ErrorLogSanitizer.forLogging(e.getCause()));
            directionFuture.cancel(true);
            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, questionCount,
                historicalSection);
        }

        try {
            directionQuestions = directionFuture.join();
        } catch (CompletionException e) {
            log.error("方向题生成失败，降级为全简历题",
                ErrorLogSanitizer.forLogging(e.getCause()));
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

    private List<InterviewQuestionDTO> generateResumeQuestions(
            ChatClient questionClient, String resumeText, int questionCount,
            SkillDTO skill, String difficultyDesc, String historicalSection) {
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

            QuestionListDTO dto = structuredOutputInvoker.invoke(
                questionClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "简历题生成失败：", "简历题", log);

            List<InterviewQuestionDTO> questions = resultAssembler.convert(dto);
            questions = resultAssembler.capToMainCount(questions, questionCount);
            log.info("简历题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.stream().filter(q -> !q.isFollowUp()).count());
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("简历题生成异常: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            throw e;
        }
    }

    private List<InterviewQuestionDTO> generateDirectionOnly(
            ChatClient questionClient, SkillDTO skill, String difficultyDesc,
            int questionCount, String historicalSection) {
        Map<String, Integer> allocation = skillService.calculateAllocation(skill.categories(), questionCount);
        String allocationTable = skillService.buildAllocationDescription(allocation, skill.categories());

        log.info("方向题生成: skill={}, total={}, allocation={}",
            skill.id(), questionCount, allocation);

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

            QuestionListDTO dto = structuredOutputInvoker.invoke(
                questionClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "方向题生成失败：", "方向题", log);

            List<InterviewQuestionDTO> questions = resultAssembler.convert(dto);
            if (questions.stream().filter(q -> !q.isFollowUp()).count() == 0) {
                log.warn("方向题返回空题单，回退到默认问题");
                return resultAssembler.fallback(skill, questionCount);
            }
            questions = resultAssembler.capToMainCount(questions, questionCount);
            log.info("方向题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.stream().filter(q -> !q.isFollowUp()).count());
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("方向题生成失败，回退到默认问题: {}",
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            return resultAssembler.fallback(skill, questionCount);
        }
    }

    private SkillDTO resolveSkill(String skillId, List<CategoryDTO> customCategories, String jdText) {
        if (InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId)
                && customCategories != null && !customCategories.isEmpty()) {
            return skillService.buildCustomSkill(customCategories, jdText != null ? jdText : "");
        }
        return skillService.getSkill(skillId);
    }

    private String resolveDifficulty(String difficulty) {
        return DIFFICULTY_DESCRIPTIONS.getOrDefault(
            difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY,
            DIFFICULTY_DESCRIPTIONS.get(InterviewDefaults.DIFFICULTY));
    }

    private String buildJdSection(String sourceJd) {
        if (sourceJd == null || sourceJd.isBlank()) {
            return "";
        }
        return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
            "## 职位描述（JD）\n根据以下 JD 关键要求出题，确保题目与岗位实际需求相关：\n" +
            promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(sourceJd));
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
