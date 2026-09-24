package interview.guide.modules.knowledgebase.service.question;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.dto.KnowledgeBaseQuestionFollowUpDTO;
import interview.guide.modules.knowledgebase.entity.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.entity.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 将结构化题目结果转换为持久化实体，并负责字段清洗和 JSON 序列化。
 */
final class KnowledgeBaseQuestionEntityFactory {

  private final ObjectMapper objectMapper;

  KnowledgeBaseQuestionEntityFactory(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  GenerationBatch build(
      KnowledgeBaseEntity knowledgeBase,
      String difficulty,
      String sourceContext,
      int followUpCount,
      KnowledgeBaseQuestionGenerationService.QuestionListDTO generated) {
    List<KnowledgeBaseQuestionEntity> entities = new ArrayList<>();
    Set<String> batchKeys = new LinkedHashSet<>();
    int skippedCount = 0;
    for (KnowledgeBaseQuestionGenerationService.QuestionDTO dto : generated.questions()) {
      if (dto == null || dto.question() == null || dto.question().isBlank()) {
        skippedCount += 1;
        continue;
      }
      String rawQuestion = dto.question().trim();
      if (!batchKeys.add(normalizeQuestionKey(rawQuestion))) {
        skippedCount += 1;
        continue;
      }
      KnowledgeBaseQuestionEntity entity = new KnowledgeBaseQuestionEntity();
      entity.setKnowledgeBase(knowledgeBase);
      entity.setSkillId(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
      entity.setDifficulty(difficulty);
      entity.setType(trimToNull(dto.type()));
      entity.setCategory(normalizeCategory(dto.category(), knowledgeBase.getName()));
      entity.setQuestion(rawQuestion);
      entity.setTopicSummary(trimToNull(dto.topicSummary()));
      entity.setReferenceAnswer(trimToNull(dto.referenceAnswer()));
      entity.setKeyPointsJson(writeStringList(dto.keyPoints()));
      entity.setScoringRubric(trimToNull(dto.scoringRubric()));
      entity.setFollowUpsJson(writeFollowUps(dto.followUps(), followUpCount));
      entity.setSourceContext(sourceContext);
      entity.setKbContentHash(knowledgeBase.getFileHash());
      entity.setStatus(KnowledgeBaseQuestionStatus.DRAFT);
      entities.add(entity);
    }
    return new GenerationBatch(entities, skippedCount);
  }

  private String normalizeCategory(String category, String fallback) {
    if (category == null || category.isBlank()) {
      return fallback != null && !fallback.isBlank() ? fallback.trim() : "未分类";
    }
    return category.trim();
  }

  private String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private String normalizeQuestionKey(String question) {
    String normalized = Normalizer.normalize(question, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    StringBuilder builder = new StringBuilder(normalized.length());
    for (int i = 0; i < normalized.length(); i += 1) {
      char ch = normalized.charAt(i);
      if (Character.isLetterOrDigit(ch)) {
        builder.append(ch);
      }
    }
    return builder.toString();
  }

  private String writeStringList(List<String> values) {
    try {
      List<String> sanitized = values == null ? List.of() : values.stream()
          .filter(value -> value != null && !value.isBlank())
          .map(String::trim)
          .toList();
      return objectMapper.writeValueAsString(sanitized);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化题目列表字段失败", e);
    }
  }

  private String writeFollowUps(List<KnowledgeBaseQuestionFollowUpDTO> values, int followUpCount) {
    try {
      List<KnowledgeBaseQuestionFollowUpDTO> sanitized = values == null ? List.of() : values.stream()
          .filter(value -> value != null && value.question() != null && !value.question().isBlank())
          .map(value -> new KnowledgeBaseQuestionFollowUpDTO(
              value.question().trim(),
              trimToNull(value.referenceAnswer()),
              value.keyPoints() == null ? List.of() : value.keyPoints().stream()
                  .filter(item -> item != null && !item.isBlank())
                  .map(String::trim)
                  .toList(),
              trimToNull(value.scoringRubric())))
          .limit(followUpCount)
          .toList();
      return objectMapper.writeValueAsString(sanitized);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化追问字段失败", e);
    }
  }

  record GenerationBatch(List<KnowledgeBaseQuestionEntity> questions, int skippedCount) {
  }
}
