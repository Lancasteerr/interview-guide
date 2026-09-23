package interview.guide.modules.knowledgebase.dto;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;

import java.time.LocalDateTime;
import java.util.List;

public record KnowledgeBaseQuestionDTO(
    Long id,
    Long knowledgeBaseId,
    String knowledgeBaseName,
    String skillId,
    String difficulty,
    String type,
    String category,
    String question,
    String topicSummary,
    String referenceAnswer,
    List<String> keyPoints,
    String scoringRubric,
    List<KnowledgeBaseQuestionFollowUpDTO> followUps,
    String sourceContext,
    KnowledgeBaseQuestionStatus status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
