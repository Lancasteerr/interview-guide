package interview.guide.modules.llmprovider.dto;

public record UpdateProviderRequest(
    String baseUrl,
    String apiKey,
    String model,
    String embeddingModel,
    Integer embeddingDimensions,
    Boolean supportsEmbedding,
    String rerankModel,
    String rerankWorkspaceId,
    Boolean supportsRerank,
    Double temperature
) {
    public UpdateProviderRequest(
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel,
        Integer embeddingDimensions,
        Boolean supportsEmbedding,
        Double temperature
    ) {
        this(baseUrl, apiKey, model, embeddingModel, embeddingDimensions, supportsEmbedding,
            null, null, null, temperature);
    }

    public UpdateProviderRequest(
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel,
        Double temperature
    ) {
        this(baseUrl, apiKey, model, embeddingModel, null, null, null, null, null,
            temperature);
    }
}
