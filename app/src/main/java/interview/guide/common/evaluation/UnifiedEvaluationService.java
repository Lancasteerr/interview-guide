package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.evaluation.EvaluationReport.CategoryScore;
import interview.guide.common.evaluation.EvaluationReport.QuestionEvaluation;
import interview.guide.common.evaluation.EvaluationReport.ReferenceAnswer;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一面试评估服务
 * 文字面试和语音面试共用的评估逻辑：分批评估 + 结构化输出 + 二次汇总 + 降级兜底
 */
@Service
public class UnifiedEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedEvaluationService.class);
    private static final int MAX_REFERENCE_CONTEXT_CHARS = 6000;

    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    private final BeanOutputConverter<SummaryDTO> summaryOutputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final ResourceLoader resourceLoader;
    private final EvaluationReportAssembler reportAssembler = new EvaluationReportAssembler();
    private final EvaluationBatchService batchService;

    // 批次评估结果
    record BatchReportDTO(
        int overallScore,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<QuestionEvalDTO> questionEvaluations
    ) {}

    record QuestionEvalDTO(
        int questionIndex,
        int score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints
    ) {}

    record BatchResult(
        List<Integer> questionIndexes,
        BatchReportDTO report
    ) {}

    /**
     * 问答组：主问题与其追问构成的不可拆分单元
     */
    record QaGroup(List<QaRecord> records) {}

    record SummaryDTO(
        String overallFeedback,
        List<String> strengths,
        List<String> improvements
    ) {}

    public UnifiedEvaluationService(
            StructuredOutputInvoker structuredOutputInvoker,
            ResourceLoader resourceLoader,
            InterviewEvaluationProperties evaluationProperties) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.resourceLoader = resourceLoader;
        PromptTemplate systemPromptTemplate =
            new PromptTemplate(loadPrompt(evaluationProperties.getSystemPromptPath()));
        PromptTemplate userPromptTemplate =
            new PromptTemplate(loadPrompt(evaluationProperties.getUserPromptPath()));
        this.summarySystemPromptTemplate =
            new PromptTemplate(loadPrompt(evaluationProperties.getSummarySystemPromptPath()));
        this.summaryUserPromptTemplate =
            new PromptTemplate(loadPrompt(evaluationProperties.getSummaryUserPromptPath()));
        BeanOutputConverter<BatchReportDTO> outputConverter = new BeanOutputConverter<>(BatchReportDTO.class);
        this.summaryOutputConverter = new BeanOutputConverter<>(SummaryDTO.class);
        this.batchService = new EvaluationBatchService(
            systemPromptTemplate,
            userPromptTemplate,
            outputConverter,
            structuredOutputInvoker,
            Math.max(1, evaluationProperties.getBatchSize()),
            evaluationProperties.isFallbackSplitEnabled(),
            Math.max(0, evaluationProperties.getFallbackMaxExtraCalls()),
            Math.max(2, evaluationProperties.getFallbackMinGroups())
        );
    }

    /**
     * 评估面试问答（文字和语音通用）
     *
     * @param chatClient  LLM 客户端
     * @param sessionId   会话ID（用于日志）
     * @param qaRecords   问答记录列表
     * @param resumeText  简历摘要（可选，可为 null）
     * @return 评估报告
     */
    public EvaluationReport evaluate(ChatClient chatClient,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText) {
        return evaluate(chatClient, sessionId, qaRecords, resumeText, null);
    }

    public EvaluationReport evaluate(ChatClient chatClient,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText,
                                     String referenceContext) {
        log.info("开始评估面试: sessionId={}, 共{}题", sessionId, qaRecords.size());

        String resumeContext = resumeText != null ? resumeText : "";
        // 超长简历截断，保留前 3000 字符（约 1500~2000 tokens），避免极端情况下 token 消耗过大
        if (resumeContext.length() > 3000) {
            resumeContext = resumeContext.substring(0, 3000) + "\n...(简历内容过长，已截断)";
        }
        String referenceBaseline = referenceContext != null ? referenceContext.trim() : "";
        if (referenceBaseline.length() > MAX_REFERENCE_CONTEXT_CHARS) {
            referenceBaseline = referenceBaseline.substring(0, MAX_REFERENCE_CONTEXT_CHARS)
                + "\n...(参考基线过长，已截断)";
        }

        // 分批评估
        List<BatchResult> batchResults = batchService.evaluate(
            chatClient, sessionId, resumeContext, qaRecords, referenceBaseline
        );

        // 合并批次结果
        List<QuestionEvalDTO> mergedEvaluations = reportAssembler.mergeQuestionEvaluations(batchResults);
        String fallbackFeedback = reportAssembler.mergeOverallFeedback(batchResults);
        List<String> fallbackStrengths = reportAssembler.mergeListItems(batchResults, true);
        List<String> fallbackImprovements = reportAssembler.mergeListItems(batchResults, false);

        // 二次汇总
        SummaryDTO summary = summarizeBatchResults(
            chatClient, sessionId, resumeContext, referenceBaseline, qaRecords,
            mergedEvaluations, fallbackFeedback, fallbackStrengths, fallbackImprovements
        );

        return reportAssembler.buildReport(sessionId, qaRecords, mergedEvaluations,
            summary.overallFeedback(), summary.strengths(), summary.improvements());
    }

    private String loadPrompt(String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 按模型返回的 questionIndex 显式映射回原始题目：
     * - 索引全部合法：按索引写回对应位置
     * - 返回数量与输入一致且索引完全不可用（无任何合法索引，或整批呈现一致的 1-based 偏移）：
     *   对整批按位置对齐
     * - 其余情况：合法索引按索引写回，非法或缺失的索引只降级该位置为 0 分，
     *   不做半索引半位置的混用（避免同一份评估被重复消费或错位）
     */
    private List<QuestionEvalDTO> mergeQuestionEvaluations(List<BatchResult> batchResults) {
        return reportAssembler.mergeQuestionEvaluations(batchResults);
    }

    /**
     * 判断返回的索引是否为整批一致的 1-based 重编号（每个原始索引 +1，且数量一致）
     */
    private boolean isConsistentOneBasedShift(Set<Integer> returnedIndexes,
                                              List<Integer> expectedIndexes) {
        Set<Integer> shifted = expectedIndexes.stream()
            .map(i -> i + 1).collect(Collectors.toSet());
        return returnedIndexes.equals(shifted);
    }

    private String mergeOverallFeedback(List<BatchResult> batchResults) {
        return reportAssembler.mergeOverallFeedback(batchResults);
    }

    private List<String> mergeListItems(List<BatchResult> batchResults, boolean strengthsMode) {
        return reportAssembler.mergeListItems(batchResults, strengthsMode);
    }

    private SummaryDTO summarizeBatchResults(
            ChatClient chatClient, String sessionId, String resumeContext, String referenceContext,
            List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations,
            String fallbackFeedback, List<String> fallbackStrengths, List<String> fallbackImprovements) {
        try {
            String summarySystem = summarySystemPromptTemplate.render();
            Map<String, Object> vars = new HashMap<>();
            vars.put("resumeText", resumeContext);
            vars.put("referenceContext",
                (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
            vars.put("categorySummary", reportAssembler.buildCategorySummary(qaRecords, evaluations));
            vars.put("questionHighlights", reportAssembler.buildQuestionHighlights(qaRecords, evaluations));
            vars.put("fallbackOverallFeedback", fallbackFeedback);
            vars.put("fallbackStrengths", String.join("\n", fallbackStrengths));
            vars.put("fallbackImprovements", String.join("\n", fallbackImprovements));
            String summaryUser = summaryUserPromptTemplate.render(vars);

            String systemWithFormat = summarySystem + "\n\n" + summaryOutputConverter.getFormat();
            SummaryDTO dto = structuredOutputInvoker.invoke(
                chatClient, systemWithFormat, summaryUser, summaryOutputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "总结评估失败：", "总结评估", log
            );

            return reportAssembler.summarize(qaRecords, evaluations, dto, fallbackFeedback,
                fallbackStrengths, fallbackImprovements);
        } catch (Exception e) {
            log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}",
                sessionId, ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            return new SummaryDTO(fallbackFeedback, fallbackStrengths, fallbackImprovements);
        }
    }

    private EvaluationReport buildReport(String sessionId, List<QaRecord> qaRecords,
                                          List<QuestionEvalDTO> evaluations,
                                          String overallFeedback,
                                          List<String> strengths, List<String> improvements) {
        return reportAssembler.buildReport(sessionId, qaRecords, evaluations, overallFeedback,
            strengths, improvements);
    }

    private String buildCategorySummary(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        return reportAssembler.buildCategorySummary(qaRecords, evaluations);
    }

    private String buildQuestionHighlights(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        return reportAssembler.buildQuestionHighlights(qaRecords, evaluations);
    }
}
