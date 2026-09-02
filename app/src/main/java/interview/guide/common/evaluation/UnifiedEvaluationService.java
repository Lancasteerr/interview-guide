package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.evaluation.EvaluationReport.CategoryScore;
import interview.guide.common.evaluation.EvaluationReport.QuestionEvaluation;
import interview.guide.common.evaluation.EvaluationReport.ReferenceAnswer;
import interview.guide.common.exception.ErrorCode;
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
    private final ResourceLoader resourceLoader;

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

    private record BatchResult(
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
        List<QuestionEvalDTO> mergedEvaluations = mergeQuestionEvaluations(batchResults);
        String fallbackFeedback = mergeOverallFeedback(batchResults);
        List<String> fallbackStrengths = mergeListItems(batchResults, true);
        List<String> fallbackImprovements = mergeListItems(batchResults, false);

        // 二次汇总
        SummaryDTO summary = summarizeBatchResults(
            chatClient, sessionId, resumeContext, referenceBaseline, qaRecords,
            mergedEvaluations, fallbackFeedback, fallbackStrengths, fallbackImprovements
        );

        return buildReport(sessionId, qaRecords, mergedEvaluations,
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
        for (List<QaRecord> batch : packBatches(buildGroups(qaRecords))) {
            BatchReportDTO report = evaluateBatch(chatClient, sessionId, resumeContext, referenceContext, batch);
            results.add(new BatchResult(
                batch.stream().map(QaRecord::questionIndex).toList(), report));
        }
        return results;
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
     * 以组为不可拆分单元装批；加入下一组会超过批次大小时先提交当前批次；
     * 单个组超过批次大小时允许其独占一个超限批次，优先保证上下文完整
     */
    private List<List<QaRecord>> packBatches(List<QaGroup> groups) {
        List<List<QaRecord>> batches = new ArrayList<>();
        List<QaRecord> current = new ArrayList<>();
        for (QaGroup group : groups) {
            if (!current.isEmpty() && current.size() + group.records().size() > evaluationBatchSize) {
                batches.add(current);
                current = new ArrayList<>();
            }
            current.addAll(group.records());
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
                sessionId, batch.size(), e.getMessage(), e);
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
        List<QuestionEvalDTO> merged = new ArrayList<>();
        for (BatchResult result : batchResults) {
            List<Integer> expectedIndexes = result.questionIndexes();
            List<QuestionEvalDTO> current =
                result.report() != null && result.report().questionEvaluations() != null
                    ? result.report().questionEvaluations()
                    : List.of();

            Map<Integer, QuestionEvalDTO> byIndex = new HashMap<>();
            Set<Integer> returnedIndexes = new LinkedHashSet<>();
            for (QuestionEvalDTO dto : current) {
                if (dto == null) {
                    continue;
                }
                returnedIndexes.add(dto.questionIndex());
                if (expectedIndexes.contains(dto.questionIndex())) {
                    byIndex.putIfAbsent(dto.questionIndex(), dto);
                }
            }
            boolean countsMatch = current.size() == expectedIndexes.size();
            boolean allMapped = byIndex.size() == expectedIndexes.size();
            boolean oneBasedShift = !allMapped && countsMatch
                && isConsistentOneBasedShift(returnedIndexes, expectedIndexes);
            boolean noUsableIndex = byIndex.isEmpty() && countsMatch;
            boolean positionalFallback = oneBasedShift || noUsableIndex;

            for (int i = 0; i < expectedIndexes.size(); i++) {
                int originalIndex = expectedIndexes.get(i);
                QuestionEvalDTO dto = positionalFallback
                    ? current.get(i)
                    : byIndex.get(originalIndex);
                if (dto != null && dto.questionIndex() != originalIndex) {
                    dto = new QuestionEvalDTO(originalIndex, dto.score(), dto.feedback(),
                        dto.referenceAnswer(), dto.keyPoints());
                }
                merged.add(dto != null ? dto : new QuestionEvalDTO(
                    originalIndex, 0, "该题未成功生成评估结果，系统按 0 分处理。", "", List.of()));
            }
        }
        return merged;
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
        String feedback = batchResults.stream()
            .map(BatchResult::report)
            .filter(r -> r != null && r.overallFeedback() != null && !r.overallFeedback().isBlank())
            .map(BatchReportDTO::overallFeedback)
            .collect(Collectors.joining("\n\n"));
        return feedback.isBlank() ? "本次面试已完成分批评估，但未生成有效综合评语。" : feedback;
    }

    private List<String> mergeListItems(List<BatchResult> batchResults, boolean strengthsMode) {
        Set<String> merged = new LinkedHashSet<>();
        for (BatchResult result : batchResults) {
            BatchReportDTO report = result.report();
            if (report == null) continue;
            List<String> items = strengthsMode ? report.strengths() : report.improvements();
            if (items == null) continue;
            items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .forEach(merged::add);
        }
        return merged.stream().limit(8).toList();
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
            vars.put("categorySummary", buildCategorySummary(qaRecords, evaluations));
            vars.put("questionHighlights", buildQuestionHighlights(qaRecords, evaluations));
            vars.put("fallbackOverallFeedback", fallbackFeedback);
            vars.put("fallbackStrengths", String.join("\n", fallbackStrengths));
            vars.put("fallbackImprovements", String.join("\n", fallbackImprovements));
            String summaryUser = summaryUserPromptTemplate.render(vars);

            String systemWithFormat = summarySystem + "\n\n" + summaryOutputConverter.getFormat();
            SummaryDTO dto = structuredOutputInvoker.invoke(
                chatClient, systemWithFormat, summaryUser, summaryOutputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "总结评估失败：", "总结评估", log
            );

            String feedback = dto != null && dto.overallFeedback() != null && !dto.overallFeedback().isBlank()
                ? dto.overallFeedback() : fallbackFeedback;
            List<String> strengths = sanitizeItems(dto != null ? dto.strengths() : null, fallbackStrengths);
            List<String> improvements = sanitizeItems(dto != null ? dto.improvements() : null, fallbackImprovements);
            return new SummaryDTO(feedback, strengths, improvements);
        } catch (Exception e) {
            log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}", sessionId, e.getMessage());
            return new SummaryDTO(fallbackFeedback, fallbackStrengths, fallbackImprovements);
        }
    }

    private List<String> sanitizeItems(List<String> primary, List<String> fallback) {
        List<String> source = (primary != null && !primary.isEmpty()) ? primary : fallback;
        if (source == null || source.isEmpty()) return List.of();
        return source.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim).distinct().limit(8).toList();
    }

    private EvaluationReport buildReport(String sessionId, List<QaRecord> qaRecords,
                                          List<QuestionEvalDTO> evaluations,
                                          String overallFeedback,
                                          List<String> strengths, List<String> improvements) {
        List<QuestionEvaluation> questionDetails = new ArrayList<>();
        List<ReferenceAnswer> referenceAnswers = new ArrayList<>();
        Map<String, List<Integer>> categoryScoresMap = new HashMap<>();

        long answeredCount = qaRecords.stream()
            .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank())
            .count();

        int evalSize = evaluations != null ? evaluations.size() : 0;

        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evalSize ? evaluations.get(i) : null;

            boolean hasAnswer = q.userAnswer() != null && !q.userAnswer().isBlank();
            int score = hasAnswer && eval != null ? eval.score() : 0;
            String feedback = eval != null && eval.feedback() != null
                ? eval.feedback() : "该题未成功生成评估反馈。";
            String refAnswer = eval != null && eval.referenceAnswer() != null
                ? eval.referenceAnswer() : "";
            List<String> keyPoints = eval != null && eval.keyPoints() != null
                ? eval.keyPoints() : List.of();

            questionDetails.add(new QuestionEvaluation(
                q.questionIndex(), q.question(), q.category(), q.userAnswer(), score, feedback
            ));
            referenceAnswers.add(new ReferenceAnswer(
                q.questionIndex(), q.question(), refAnswer, keyPoints
            ));
            categoryScoresMap.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }

        List<CategoryScore> categoryScores = categoryScoresMap.entrySet().stream()
            .map(e -> new CategoryScore(
                e.getKey(),
                (int) e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                e.getValue().size()
            ))
            .collect(Collectors.toList());

        int overallScore = answeredCount == 0 ? 0
            : (int) questionDetails.stream().mapToInt(QuestionEvaluation::score).average().orElse(0);

        return new EvaluationReport(
            sessionId, qaRecords.size(), overallScore, categoryScores, questionDetails,
            overallFeedback,
            strengths != null ? strengths : List.of(),
            improvements != null ? improvements : List.of(),
            referenceAnswers
        );
    }

    private String buildCategorySummary(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        Map<String, List<Integer>> categoryScores = new HashMap<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = 0;
            if (eval != null && q.userAnswer() != null && !q.userAnswer().isBlank()) {
                score = eval.score();
            }
            categoryScores.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }
        return categoryScores.entrySet().stream()
            .map(entry -> {
                int avg = (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
                return String.format("- %s: 平均分 %d, 题数 %d", entry.getKey(), avg, entry.getValue().size());
            })
            .sorted()
            .collect(Collectors.joining("\n"));
    }

    private String buildQuestionHighlights(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        List<String> highlights = new ArrayList<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = eval != null ? eval.score() : 0;
            String feedback = eval != null && eval.feedback() != null ? eval.feedback() : "";
            String shortQ = q.question().length() > 50 ? q.question().substring(0, 50) + "..." : q.question();
            String shortF = feedback.length() > 80 ? feedback.substring(0, 80) + "..." : feedback;
            highlights.add(String.format("- Q%d | %s | 分数:%d | 反馈:%s", q.questionIndex() + 1, shortQ, score, shortF));
        }
        return highlights.stream().limit(20).collect(Collectors.joining("\n"));
    }
}
