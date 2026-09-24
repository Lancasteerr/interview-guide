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
import java.util.ArrayList;
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

    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<BatchReportDTO> outputConverter;
    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    private final BeanOutputConverter<SummaryDTO> summaryOutputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final int evaluationBatchSize;
    private final boolean fallbackSplitEnabled;
    private final int fallbackMaxExtraCalls;
    private final int fallbackMinGroups;
    private final ResourceLoader resourceLoader;
    private final EvaluationReportAssembler reportAssembler = new EvaluationReportAssembler();

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
    private record QaGroup(List<QaRecord> records) {}

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
        this.systemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSystemPromptPath()));
        this.userPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getUserPromptPath()));
        this.outputConverter = new BeanOutputConverter<>(BatchReportDTO.class);
        this.summarySystemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummarySystemPromptPath()));
        this.summaryUserPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummaryUserPromptPath()));
        this.summaryOutputConverter = new BeanOutputConverter<>(SummaryDTO.class);
        this.evaluationBatchSize = Math.max(1, evaluationProperties.getBatchSize());
        this.fallbackSplitEnabled = evaluationProperties.isFallbackSplitEnabled();
        this.fallbackMaxExtraCalls = Math.max(0, evaluationProperties.getFallbackMaxExtraCalls());
        this.fallbackMinGroups = Math.max(2, evaluationProperties.getFallbackMinGroups());
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
        List<BatchResult> batchResults = evaluateInBatches(
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

    private List<BatchResult> evaluateInBatches(ChatClient chatClient, String sessionId,
                                                 String resumeContext, List<QaRecord> qaRecords,
                                                 String referenceContext) {
        List<BatchResult> results = new ArrayList<>();
        int[] extraBudget = {fallbackMaxExtraCalls};
        for (List<QaGroup> batchGroups : packBatches(buildGroups(qaRecords))) {
            List<QaRecord> flattened = batchGroups.stream()
                .flatMap(group -> group.records().stream()).toList();
            BatchReportDTO report = evaluateBatch(chatClient, sessionId, resumeContext,
                referenceContext, flattened);
            if (report == null && fallbackSplitEnabled) {
                report = recoverBatch(chatClient, sessionId, resumeContext, referenceContext,
                    batchGroups, extraBudget, 0);
            }
            results.add(new BatchResult(
                flattened.stream().map(QaRecord::questionIndex).toList(), report));
        }
        return results;
    }

    /**
     * 批次失败后的按组二分恢复。不会对已失败的组集合重复调用：
     * 可拆（组数 >= fallbackMinGroups）时立即二分，左右各自先 evaluate（耗预算）再对失败侧递归；
     * 不可拆时耗 1 次预算整段加试一次，仍失败则整段降级。追问组是不可拆分单元，永不逐题拆开。
     */
    private BatchReportDTO recoverBatch(ChatClient chatClient, String sessionId, String resumeContext,
                                        String referenceContext, List<QaGroup> groups,
                                        int[] extraBudget, int depth) {
        List<Integer> indexes = groups.stream()
            .flatMap(g -> g.records().stream()).map(QaRecord::questionIndex).toList();
        if (extraBudget[0] <= 0) {
            log.warn("评估批次恢复预算耗尽，降级: sessionId={}, groups={}, size={}, depth={}, failedIndexes={}",
                sessionId, groups.size(), indexes.size(), depth, indexes);
            return degradedReport(indexes);
        }
        if (groups.size() >= fallbackMinGroups) {
            int mid = groups.size() / 2;
            List<List<QaGroup>> halves = List.of(groups.subList(0, mid), groups.subList(mid, groups.size()));
            // 先评价兄弟半批（健康侧先拿到预算），再对失败侧递归下钻
            BatchReportDTO[] halfReports = new BatchReportDTO[halves.size()];
            boolean[] halfFailed = new boolean[halves.size()];
            for (int i = 0; i < halves.size(); i++) {
                List<QaRecord> flattened = halves.get(i).stream()
                    .flatMap(g -> g.records().stream()).toList();
                if (extraBudget[0] <= 0) {
                    halfReports[i] = degradedReport(
                        flattened.stream().map(QaRecord::questionIndex).toList());
                    continue;
                }
                extraBudget[0] = extraBudget[0] - 1;
                halfReports[i] = evaluateBatch(chatClient, sessionId, resumeContext,
                    referenceContext, flattened);
                halfFailed[i] = halfReports[i] == null;
            }
            List<QuestionEvalDTO> merged = new ArrayList<>();
            for (int i = 0; i < halves.size(); i++) {
                List<QaRecord> flattened = halves.get(i).stream()
                    .flatMap(g -> g.records().stream()).toList();
                if (halfFailed[i]) {
                    halfReports[i] = recoverBatch(chatClient, sessionId, resumeContext, referenceContext,
                        halves.get(i), extraBudget, depth + 1);
                }
                merged.addAll(halfReports[i].questionEvaluations() != null
                    ? halfReports[i].questionEvaluations() : degradedReport(
                        flattened.stream().map(QaRecord::questionIndex).toList()).questionEvaluations());
            }
            return new BatchReportDTO(0, "", List.of(), List.of(), merged);
        }
        // 不可再拆：整段加试一次（耗预算），仍失败则该段降级
        List<QaRecord> flattened = groups.stream().flatMap(g -> g.records().stream()).toList();
        extraBudget[0] = extraBudget[0] - 1;
        BatchReportDTO retried = evaluateBatch(chatClient, sessionId, resumeContext,
            referenceContext, flattened);
        if (retried == null) {
            log.warn("评估批次恢复最终失败，降级: sessionId={}, groups={}, size={}, depth={}, failedIndexes={}",
                sessionId, groups.size(), indexes.size(), depth, indexes);
            return degradedReport(indexes);
        }
        return retried;
    }

    /**
     * 显式降级结果：0 分 + 「模型评估失败后的系统降级」反馈。
     * 与 merge 阶段缺索引的默认兜底句区分，不伪装成真实评分。
     */
    private BatchReportDTO degradedReport(List<Integer> indexes) {
        List<QuestionEvalDTO> evaluations = indexes.stream()
            .map(index -> new QuestionEvalDTO(index, 0,
                "模型评估失败后的系统降级，该分数不代表真实表现。", "", List.of()))
            .toList();
        return new BatchReportDTO(0, "模型评估失败后的系统降级。", List.of(), List.of(), evaluations);
    }

    /**
     * 按输入顺序建立问答组：主问题为组 Key，合法追问挂到父问题所在组；
     * 父问题不存在、父索引指向未来题目等异常关系降级为独立组并告警
     */
    private List<QaGroup> buildGroups(List<QaRecord> qaRecords) {
        Set<Integer> knownIndexes = qaRecords.stream()
            .map(QaRecord::questionIndex).collect(Collectors.toSet());
        Map<Integer, QaGroup> groupByIndex = new HashMap<>();
        List<QaGroup> orderedGroups = new ArrayList<>();
        for (QaRecord q : qaRecords) {
            QaGroup target = null;
            if (q.followUp() && q.parentQuestionIndex() != null) {
                int parent = q.parentQuestionIndex();
                if (knownIndexes.contains(parent) && parent < q.questionIndex()) {
                    target = groupByIndex.get(parent);
                } else {
                    log.warn("追问父索引异常，降级为独立组: questionIndex={}, parentQuestionIndex={}",
                        q.questionIndex(), parent);
                }
            }
            if (target == null) {
                target = new QaGroup(new ArrayList<>());
                orderedGroups.add(target);
            }
            target.records().add(q);
            groupByIndex.put(q.questionIndex(), target);
        }
        return orderedGroups;
    }

    /**
     * 以组为不可拆分单元装批（保留组边界供失败恢复二分）；
     * 加入下一组会超过批次大小时先提交当前批次；
     * 单个组超过批次大小时允许其独占一个超限批次，优先保证上下文完整
     */
    private List<List<QaGroup>> packBatches(List<QaGroup> groups) {
        List<List<QaGroup>> batches = new ArrayList<>();
        List<QaGroup> current = new ArrayList<>();
        int currentSize = 0;
        for (QaGroup group : groups) {
            if (currentSize > 0 && currentSize + group.records().size() > evaluationBatchSize) {
                batches.add(current);
                current = new ArrayList<>();
                currentSize = 0;
            }
            current.add(group);
            currentSize += group.records().size();
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private BatchReportDTO evaluateBatch(ChatClient chatClient, String sessionId,
                                          String resumeContext, String referenceContext,
                                          List<QaRecord> batch) {
        String qaRecords = buildQARecords(batch);
        String systemPrompt = systemPromptTemplate.render();

        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeContext);
        variables.put("qaRecords", qaRecords);
        variables.put("referenceContext",
            (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
        String userPrompt = userPromptTemplate.render(variables);

        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
        try {
            return structuredOutputInvoker.invoke(
                chatClient, systemPromptWithFormat, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "批次评估失败：", "批次评估", log
            );
        } catch (Exception e) {
            log.error("批次评估失败: sessionId={}, batchSize={}, error={}",
                sessionId, batch.size(), ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            // 返回空报告，让合并逻辑用零分兜底
            return null;
        }
    }

    private String buildQARecords(List<QaRecord> batch) {
        StringBuilder sb = new StringBuilder();
        for (QaRecord q : batch) {
            String relation = q.followUp() && q.parentQuestionIndex() != null
                ? String.format("（追问，针对 questionIndex=%d）", q.parentQuestionIndex())
                : "";
            sb.append(String.format("问题 questionIndex=%d [%s]%s: %s\n",
                q.questionIndex(), q.category(), relation, q.question()));
            sb.append(String.format("回答: %s\n\n",
                q.userAnswer() != null ? q.userAnswer() : "(未回答)"));
        }
        return sb.toString();
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
