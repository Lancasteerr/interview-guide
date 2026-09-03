package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;

    private final LlmProviderRegistry llmProviderRegistry;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;

    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
    }

    private ChatClient getChatClient() {
        return llmProviderRegistry.getPlainChatClient();
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        String normalized = normalizeQuestion(question);
        log.info("收到知识库提问: kbIds={}, questionLength={}", knowledgeBaseIds, normalized.length());
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalized.isBlank()) {
            return NO_RESULT_RESPONSE;
        }

        countService.updateQuestionCounts(knowledgeBaseIds);

        QueryContext queryContext = buildQueryContext(question, List.of());
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

        if (!hasEffectiveHit(relevantDocs)) {
            return NO_RESULT_RESPONSE;
        }

        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question);

        try {
            String answer = getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);

            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return answer;

        } catch (Exception e) {
            log.error("知识库问答失败: {}", ErrorLogSanitizer.summarize(e), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        String answer = answerQuestion(request.knowledgeBaseIds(), request.question());

        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        return new QueryResponse(answer, primaryKbId, kbNamesStr);
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        return answerQuestionStream(knowledgeBaseIds, question, history, null);
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文），可选地输出完整执行轨迹。
     * 轨迹收集器仅供测评与可观测性使用，不影响业务行为。
     *
     * @param trace 执行轨迹收集器（可为 null，为 null 时行为与无收集器入口完全一致）
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history,
                                             java.util.function.Consumer<RagQueryExecution> trace) {
        String normalized = normalizeQuestion(question);
        log.info("收到知识库流式提问: kbIds={}, questionLength={}, historySize={}", knowledgeBaseIds,
                normalized.length(), history != null ? history.size() : 0);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalized.isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2. Query rewrite + 动态参数检索
            long rewriteStart = System.nanoTime();
            List<Message> effectiveHistory = sanitizeHistory(history);
            QueryContext queryContext = buildQueryContext(question, effectiveHistory);
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);
            long rewriteAndRetrievalMs = (System.nanoTime() - rewriteStart) / 1_000_000;

            if (!hasEffectiveHit(relevantDocs)) {
                emitTrace(trace, question, queryContext, relevantDocs,
                    rewriteAndRetrievalMs, 0, NO_RESULT_RESPONSE, "NO_RESULT");
                return Flux.just(NO_RESULT_RESPONSE);
            }

            // 3. 构建上下文
            String context = relevantDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用（带历史上下文）+ 探测窗口归一化
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!effectiveHistory.isEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory);
            }
            Flux<String> responseFlux = promptSpec
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
            long generationStart = System.nanoTime();
            StringBuilder collectedAnswer = new StringBuilder();
            return normalizeStreamOutput(responseFlux)
                .doOnNext(collectedAnswer::append)
                .doOnComplete(() -> {
                    long generationMs = (System.nanoTime() - generationStart) / 1_000_000;
                    boolean rejected = NO_RESULT_RESPONSE.equals(collectedAnswer.toString());
                    emitTrace(trace, question, queryContext, relevantDocs,
                        rewriteAndRetrievalMs, generationMs, collectedAnswer.toString(),
                        rejected ? "NO_RESULT" : "ANSWERED");
                    log.info("流式输出完成: kbIds={}", knowledgeBaseIds);
                })
                .onErrorResume(e -> {
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, ErrorLogSanitizer.summarize(e), e);
                    emitTrace(trace, question, queryContext, List.of(),
                        rewriteAndRetrievalMs, (System.nanoTime() - generationStart) / 1_000_000,
                        "【错误】知识库查询失败", "ERROR");
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                });

        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", ErrorLogSanitizer.summarize(e), e);
            emitTrace(trace, normalizeQuestion(question), null, List.of(),
                0, 0, "【错误】知识库查询失败", "ERROR");
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 输出执行轨迹（收集器为 null 时直接返回）。
     */
    private void emitTrace(java.util.function.Consumer<RagQueryExecution> trace,
                           String originalQuestion, QueryContext queryContext,
                           List<Document> chosenDocs,
                           long rewriteAndRetrievalMs, long generationMs,
                           String answer, String outcome) {
        if (trace == null) {
            return;
        }
        List<String> attemptedQueries = queryContext != null
            ? queryContext.candidateQueries()
            : List.of(normalizeQuestion(originalQuestion));
        List<RagQueryExecution.RetrievedDoc> docs = new ArrayList<>();
        for (int i = 0; i < chosenDocs.size(); i++) {
            Document doc = chosenDocs.get(i);
            docs.add(new RagQueryExecution.RetrievedDoc(
                i + 1, doc.getText(), extractScore(doc), doc.getMetadata()));
        }
        SearchParams params = queryContext != null ? queryContext.searchParams()
            : new SearchParams(topkLong, minScoreDefault);
        long rewriteMs = queryContext != null ? queryContext.rewriteDurationMs() : 0;
        trace.accept(new RagQueryExecution(
            normalizeQuestion(originalQuestion),
            queryContext != null && !queryContext.candidateQueries().isEmpty()
                ? queryContext.candidateQueries().getFirst() : normalizeQuestion(originalQuestion),
            attemptedQueries,
            params.topK(),
            params.minScore(),
            List.copyOf(docs),
            rewriteMs,
            Math.max(0, rewriteAndRetrievalMs - rewriteMs),
            generationMs,
            answer,
            outcome));
    }

    private Double extractScore(Document doc) {
        Object score = doc.getMetadata().get("distance");
        return score != null && score instanceof Number number ? number.doubleValue() : null;
    }


    /**
     * 仅执行检索链路（改写 + 候选检索），不做 LLM 生成。
     * 供 RAG 测评复用生产检索逻辑，避免在测评代码中复制改写与分档规则。
     */
    public RagQueryExecution retrieveOnly(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        String normalized = normalizeQuestion(question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalized.isBlank()) {
            return new RagQueryExecution(normalized, normalized, List.of(normalized),
                topkLong, minScoreDefault, List.of(), 0, 0, 0, NO_RESULT_RESPONSE, "NO_RESULT");
        }
        long start = System.nanoTime();
        List<Message> effectiveHistory = sanitizeHistory(history);
        QueryContext queryContext = buildQueryContext(question, effectiveHistory);
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        boolean hit = hasEffectiveHit(relevantDocs);
        return new RagQueryExecution(
            normalized,
            queryContext.candidateQueries().getFirst(),
            queryContext.candidateQueries(),
            queryContext.searchParams().topK(),
            queryContext.searchParams().minScore(),
            relevantDocs.stream()
                .map(doc -> new RagQueryExecution.RetrievedDoc(
                    relevantDocs.indexOf(doc) + 1, doc.getText(), extractScore(doc), doc.getMetadata()))
                .toList(),
            queryContext.rewriteDurationMs(),
            Math.max(0, elapsedMs - queryContext.rewriteDurationMs()),
            0,
            null,
            hit ? "RETRIEVED" : "NO_RESULT");
    }

    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        long rewriteStart = System.nanoTime();
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);
        long rewriteDurationMs = (System.nanoTime() - rewriteStart) / 1_000_000;
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams, rewriteDurationMs);
    }

    private List<Message> sanitizeHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history;
    }

//       清洗
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

//    向量检索
    private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        List<String> candidates = queryContext.candidateQueries();
        for (int i = 0; i < candidates.size(); i++) {
            String candidateQuery = candidates.get(i);
            if (candidateQuery.isBlank()) {
                continue;
            }
            long startNanos = System.nanoTime();
            List<Document> docs = vectorService.similaritySearch(
                candidateQuery,
                knowledgeBaseIds,
                queryContext.searchParams().topK(),
                queryContext.searchParams().minScore()
            );
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("RAG 检索完成: kbCount={}, questionLength={}, queryVariant={}, hits={}, durationMs={}",
                knowledgeBaseIds.size(), candidateQuery.length(),
                i == 0 ? "rewritten" : "original", docs.size(), durationMs);
            if (hasEffectiveHit(docs)) {
                return docs;
            }
        }
        return List.of();
    }

    private SearchParams resolveSearchParams(String question) {
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, minScoreDefault);
    }

//    改写
    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled || question.isBlank()) {
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = getChatClient().prompt()
                .user(rewritePrompt)
                .call()
                .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            log.info("Query rewrite 完成: originLength={}, rewrittenLength={}, changed={}, historySize={}",
                question.length(), normalized.length(), !normalized.equals(question), history.size());
            return normalized;
        } catch (Exception e) {
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", ErrorLogSanitizer.summarize(e), e);
            return question;
        }
    }

    /**
     * 将历史消息格式化为重写 prompt 中的文本摘要。
     * 每条消息格式：用户: xxx / 助手: xxx
     */
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                // 截断过长的助手回复，避免 rewrite prompt 过长
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private boolean hasEffectiveHit(List<Document> docs) {
        return docs != null && !docs.isEmpty();
    }

    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    /**
     * 先观察前一小段流式内容，快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder();
            AtomicBoolean passthrough = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                chunk -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (passthrough.get()) {
                        sink.next(chunk);
                        return;
                    }

                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();
                    if (isNoResultLike(probeText)) {
                        completed.set(true);
                        sink.next(NO_RESULT_RESPONSE);
                        sink.complete();
                        if (disposableRef[0] != null) {
                            disposableRef[0].dispose();
                        }
                        return;
                    }

                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        passthrough.set(true);
                        sink.next(probeText);
                        probeBuffer.setLength(0);
                    }
                },
                sink::error,
                () -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()));
                    }
                    sink.complete();
                }
            );

            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    private record SearchParams(int topK, double minScore) {
    }

    private record QueryContext(String originalQuestion, List<String> candidateQueries, SearchParams searchParams,
                                long rewriteDurationMs) {
    }
}
