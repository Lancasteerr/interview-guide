package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.entity.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.entity.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository.CategoryCount;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 为知识库题目生成准备检索上下文和去重提示信息。
 */
final class KnowledgeBaseQuestionContextService {

  private static final int RETRIEVAL_TOP_K = 12;
  private static final int RETRIEVAL_QUERY_TOP_K = 4;
  private static final int MAX_CONTEXT_CHARS = 5000;

  private final KnowledgeBaseQuestionRepository questionRepository;
  private final KnowledgeBaseVectorService vectorService;

  KnowledgeBaseQuestionContextService(
      KnowledgeBaseQuestionRepository questionRepository,
      KnowledgeBaseVectorService vectorService) {
    this.questionRepository = questionRepository;
    this.vectorService = vectorService;
  }

  String buildGenerationContext(KnowledgeBaseEntity knowledgeBase) {
    List<Document> documents = new ArrayList<>();
    Set<String> seenTexts = new LinkedHashSet<>();
    for (String query : buildGenerationQueries()) {
      List<Document> hits = vectorService.similaritySearch(
          query, List.of(knowledgeBase.getId()), RETRIEVAL_QUERY_TOP_K, 0);
      for (Document document : hits) {
        String text = document.getText();
        if (text == null || text.isBlank() || !seenTexts.add(text.trim())) {
          continue;
        }
        documents.add(document);
        if (documents.size() >= RETRIEVAL_TOP_K) {
          break;
        }
      }
      if (documents.size() >= RETRIEVAL_TOP_K) {
        break;
      }
    }
    if (documents.isEmpty()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
          "知识库未检索到可用于生成题目的内容");
    }
    String context = documents.stream()
        .map(Document::getText)
        .collect(Collectors.joining("\n\n---\n\n"));
    return context.length() <= MAX_CONTEXT_CHARS
        ? context
        : context.substring(0, MAX_CONTEXT_CHARS) + "\n...(知识库片段过长，已截断)";
  }

  String buildExistingCategorySection(Long knowledgeBaseId) {
    List<CategoryCount> categories = questionRepository.findCategoryCounts(knowledgeBaseId);
    if (categories.isEmpty()) {
      return "暂无已有方向";
    }
    return categories.stream()
        .limit(10)
        .map(category -> "- " + category.getCategory() + "（" + category.getCount() + " 题）")
        .collect(Collectors.joining("\n"));
  }

  String buildExistingQuestionSection(Long knowledgeBaseId, String difficulty) {
    List<String> questions = questionRepository
        .findTop20ByKnowledgeBase_IdAndDifficultyOrderByUpdatedAtDesc(knowledgeBaseId, difficulty)
        .stream()
        .map(KnowledgeBaseQuestionEntity::getQuestion)
        .filter(question -> question != null && !question.isBlank())
        .map(question -> "- " + question.trim())
        .toList();
    return questions.isEmpty() ? "暂无已有题目" : String.join("\n", questions);
  }

  private List<String> buildGenerationQueries() {
    return List.of(
        "核心概念 定义 背景 原理",
        "关键流程 步骤 方法 工作机制",
        "规则约束 条件 边界 例外 限制",
        "典型案例 常见问题 应用场景 最佳实践"
    );
  }
}
