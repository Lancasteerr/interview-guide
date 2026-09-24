package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 负责统一评测的分组、装批、批次调用和失败恢复。
 */
final class EvaluationBatchService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationBatchService.class);

    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<UnifiedEvaluationService.BatchReportDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final int evaluationBatchSize;
    private final boolean fallbackSplitEnabled;
    private final int fallbackMaxExtraCalls;
    private final int fallbackMinGroups;

    EvaluationBatchService(
        PromptTemplate systemPromptTemplate,
        PromptTemplate userPromptTemplate,
        BeanOutputConverter<UnifiedEvaluationService.BatchReportDTO> outputConverter,
        StructuredOutputInvoker structuredOutputInvoker,
        int evaluationBatchSize,
        boolean fallbackSplitEnabled,
        int fallbackMaxExtraCalls,
        int fallbackMinGroups) {
        this.systemPromptTemplate = systemPromptTemplate;
        this.userPromptTemplate = userPromptTemplate;
        this.outputConverter = outputConverter;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.evaluationBatchSize = evaluationBatchSize;
        this.fallbackSplitEnabled = fallbackSplitEnabled;
        this.fallbackMaxExtraCalls = fallbackMaxExtraCalls;
        this.fallbackMinGroups = fallbackMinGroups;
    }

    List<UnifiedEvaluationService.BatchResult> evaluate(
        ChatClient chatClient,
        String sessionId,
        String resumeContext,
        List<QaRecord> qaRecords,
        String referenceContext) {
        List<UnifiedEvaluationService.BatchResult> results = new ArrayList<>();
        int[] extraBudget = {fallbackMaxExtraCalls};
        for (List<UnifiedEvaluationService.QaGroup> batchGroups : packBatches(buildGroups(qaRecords))) {
            List<QaRecord> flattened = batchGroups.stream()
                .flatMap(group -> group.records().stream()).toList();
            UnifiedEvaluationService.BatchReportDTO report = evaluateBatch(
                chatClient, sessionId, resumeContext, referenceContext, flattened);
            if (report == null && fallbackSplitEnabled) {
                report = recoverBatch(
                    chatClient, sessionId, resumeContext, referenceContext,
                    batchGroups, extraBudget, 0);
            }
            results.add(new UnifiedEvaluationService.BatchResult(
                flattened.stream().map(QaRecord::questionIndex).toList(), report));
        }
        return results;
    }

    private UnifiedEvaluationService.BatchReportDTO recoverBatch(
        ChatClient chatClient,
        String sessionId,
        String resumeContext,
        String referenceContext,
        List<UnifiedEvaluationService.QaGroup> groups,
        int[] extraBudget,
        int depth) {
        List<Integer> indexes = groups.stream()
            .flatMap(group -> group.records().stream())
            .map(QaRecord::questionIndex)
            .toList();
        if (extraBudget[0] <= 0) {
            log.warn("评估批次恢复预算耗尽，降级: sessionId={}, groups={}, size={}, depth={}, failedIndexes={}",
                sessionId, groups.size(), indexes.size(), depth, indexes);
            return degradedReport(indexes);
        }
        if (groups.size() >= fallbackMinGroups) {
            int mid = groups.size() / 2;
            List<List<UnifiedEvaluationService.QaGroup>> halves = List.of(
                groups.subList(0, mid), groups.subList(mid, groups.size()));
            UnifiedEvaluationService.BatchReportDTO[] halfReports =
                new UnifiedEvaluationService.BatchReportDTO[halves.size()];
            boolean[] halfFailed = new boolean[halves.size()];
            for (int i = 0; i < halves.size(); i++) {
                List<QaRecord> flattened = halves.get(i).stream()
                    .flatMap(group -> group.records().stream()).toList();
                if (extraBudget[0] <= 0) {
                    halfReports[i] = degradedReport(
                        flattened.stream().map(QaRecord::questionIndex).toList());
                    continue;
                }
                extraBudget[0] = extraBudget[0] - 1;
                halfReports[i] = evaluateBatch(
                    chatClient, sessionId, resumeContext, referenceContext, flattened);
                halfFailed[i] = halfReports[i] == null;
            }
            List<UnifiedEvaluationService.QuestionEvalDTO> merged = new ArrayList<>();
            for (int i = 0; i < halves.size(); i++) {
                List<QaRecord> flattened = halves.get(i).stream()
                    .flatMap(group -> group.records().stream()).toList();
                if (halfFailed[i]) {
                    halfReports[i] = recoverBatch(
                        chatClient, sessionId, resumeContext, referenceContext,
                        halves.get(i), extraBudget, depth + 1);
                }
                merged.addAll(halfReports[i].questionEvaluations() != null
                    ? halfReports[i].questionEvaluations()
                    : degradedReport(
                        flattened.stream().map(QaRecord::questionIndex).toList()).questionEvaluations());
            }
            return new UnifiedEvaluationService.BatchReportDTO(0, "", List.of(), List.of(), merged);
        }

        List<QaRecord> flattened = groups.stream()
            .flatMap(group -> group.records().stream()).toList();
        extraBudget[0] = extraBudget[0] - 1;
        UnifiedEvaluationService.BatchReportDTO retried = evaluateBatch(
            chatClient, sessionId, resumeContext, referenceContext, flattened);
        if (retried == null) {
            log.warn("评估批次恢复最终失败，降级: sessionId={}, groups={}, size={}, depth={}, failedIndexes={}",
                sessionId, groups.size(), indexes.size(), depth, indexes);
            return degradedReport(indexes);
        }
        return retried;
    }

    private UnifiedEvaluationService.BatchReportDTO degradedReport(List<Integer> indexes) {
        List<UnifiedEvaluationService.QuestionEvalDTO> evaluations = indexes.stream()
            .map(index -> new UnifiedEvaluationService.QuestionEvalDTO(
                index, 0, "模型评估失败后的系统降级，该分数不代表真实表现。", "", List.of()))
            .toList();
        return new UnifiedEvaluationService.BatchReportDTO(
            0, "模型评估失败后的系统降级。", List.of(), List.of(), evaluations);
    }

    private List<UnifiedEvaluationService.QaGroup> buildGroups(List<QaRecord> qaRecords) {
        Set<Integer> knownIndexes = qaRecords.stream()
            .map(QaRecord::questionIndex).collect(Collectors.toSet());
        Map<Integer, UnifiedEvaluationService.QaGroup> groupByIndex = new HashMap<>();
        List<UnifiedEvaluationService.QaGroup> orderedGroups = new ArrayList<>();
        for (QaRecord q : qaRecords) {
            UnifiedEvaluationService.QaGroup target = null;
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
                target = new UnifiedEvaluationService.QaGroup(new ArrayList<>());
                orderedGroups.add(target);
            }
            target.records().add(q);
            groupByIndex.put(q.questionIndex(), target);
        }
        return orderedGroups;
    }

    private List<List<UnifiedEvaluationService.QaGroup>> packBatches(
        List<UnifiedEvaluationService.QaGroup> groups) {
        List<List<UnifiedEvaluationService.QaGroup>> batches = new ArrayList<>();
        List<UnifiedEvaluationService.QaGroup> current = new ArrayList<>();
        int currentSize = 0;
        for (UnifiedEvaluationService.QaGroup group : groups) {
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

    private UnifiedEvaluationService.BatchReportDTO evaluateBatch(
        ChatClient chatClient,
        String sessionId,
        String resumeContext,
        String referenceContext,
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
}
