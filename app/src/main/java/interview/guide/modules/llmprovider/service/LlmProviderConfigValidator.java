package interview.guide.modules.llmprovider.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;

/**
 * Provider 的 Embedding 与 Rerank 配置规则。
 */
final class LlmProviderConfigValidator {

    private static final String DASHSCOPE_PROVIDER_ID = "dashscope";
    private static final int MAX_RERANK_CONFIG_LENGTH = 128;

    void validateEmbeddingConfig(
            String providerId,
            boolean supportsEmbedding,
            String embeddingModel,
            Integer embeddingDimensions) {
        String normalizedModel = trimOrNull(embeddingModel);
        if (!supportsEmbedding) {
            return;
        }
        if (normalizedModel == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "支持 Embedding 的 Provider 必须填写 embeddingModel");
        }
        if (embeddingDimensions == null || embeddingDimensions <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "向量维度必须为正整数");
        }
    }

    void validateRerankConfig(
            String providerId,
            boolean supportsRerank,
            String rerankModel,
            String rerankWorkspaceId) {
        String normalizedModel = trimOrNull(rerankModel);
        String normalizedWorkspaceId = trimOrNull(rerankWorkspaceId);
        boolean hasRerankConfig = supportsRerank
                || normalizedModel != null
                || normalizedWorkspaceId != null;
        if (!DASHSCOPE_PROVIDER_ID.equalsIgnoreCase(providerId) && hasRerankConfig) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "当前仅 DashScope Provider 支持配置 Rerank");
        }
        if (!supportsRerank) {
            if (normalizedModel != null || normalizedWorkspaceId != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "配置 Rerank 模型或 Workspace ID 前必须启用 Rerank");
            }
            return;
        }
        if (normalizedModel == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "支持 Rerank 的 DashScope Provider 必须填写 rerankModel");
        }
        if (normalizedWorkspaceId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "支持 Rerank 的 DashScope Provider 必须填写 rerankWorkspaceId");
        }
        if (normalizedModel.length() > MAX_RERANK_CONFIG_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "rerankModel 长度不能超过 " + MAX_RERANK_CONFIG_LENGTH);
        }
        if (normalizedWorkspaceId.length() > MAX_RERANK_CONFIG_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "rerankWorkspaceId 长度不能超过 " + MAX_RERANK_CONFIG_LENGTH);
        }
    }

    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
