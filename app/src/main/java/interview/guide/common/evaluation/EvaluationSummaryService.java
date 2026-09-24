package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责统一评测的二次汇总和汇总失败降级。
 */
final class EvaluationSummaryService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationSummaryService.class);

    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    private final BeanOutputConverter<UnifiedEvaluationService.SummaryDTO> summaryOutputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final EvaluationReportAssembler reportAssembler;

    EvaluationSummaryService(
        PromptTemplate summarySystemPromptTemplate,
        PromptTemplate summaryUserPromptTemplate,
        BeanOutputConverter<UnifiedEvaluationService.SummaryDTO> summaryOutputConverter,
        StructuredOutputInvoker structuredOutputInvoker,
        EvaluationReportAssembler reportAssembler) {
        this.summarySystemPromptTemplate = summarySystemPromptTemplate;
        this.summaryUserPromptTemplate = summaryUserPromptTemplate;
        this.summaryOutputConverter = summaryOutputConverter;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.reportAssembler = reportAssembler;
    }

    UnifiedEvaluationService.SummaryDTO summarize(
        ChatClient chatClient,
        String sessionId,
        String resumeContext,
        String referenceContext,
        List<QaRecord> qaRecords,
        List<UnifiedEvaluationService.QuestionEvalDTO> evaluations,
        String fallbackFeedback,
        List<String> fallbackStrengths,
        List<String> fallbackImprovements) {
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
            UnifiedEvaluationService.SummaryDTO dto = structuredOutputInvoker.invoke(
                chatClient, systemWithFormat, summaryUser, summaryOutputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "总结评估失败：", "总结评估", log
            );

            return reportAssembler.summarize(
                qaRecords, evaluations, dto, fallbackFeedback,
                fallbackStrengths, fallbackImprovements);
        } catch (Exception e) {
            log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}",
                sessionId, ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            return new UnifiedEvaluationService.SummaryDTO(
                fallbackFeedback, fallbackStrengths, fallbackImprovements);
        }
    }
}
