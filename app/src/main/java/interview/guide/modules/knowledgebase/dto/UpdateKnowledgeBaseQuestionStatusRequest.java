package interview.guide.modules.knowledgebase.dto;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;

import jakarta.validation.constraints.NotNull;

public record UpdateKnowledgeBaseQuestionStatusRequest(
    @NotNull(message = "题目状态不能为空")
    KnowledgeBaseQuestionStatus status
) {
}
