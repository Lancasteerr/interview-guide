package interview.guide.modules.knowledgebase.dto;

import interview.guide.modules.knowledgebase.model.QuestionGenerationConfig;
import interview.guide.modules.knowledgebase.model.QuestionGenStatus;

import java.time.LocalDateTime;

/**
 * 问题生成任务状态响应。
 */
public record QuestionGenStatusResponse(
    Long knowledgeBaseId,
    QuestionGenStatus questionGenStatus,
    String questionGenTaskId,
    QuestionGenerationConfig questionGenConfig,
    int savedCount,
    int skippedCount,
    String message,
    String error,
    LocalDateTime updatedAt
) {
}
