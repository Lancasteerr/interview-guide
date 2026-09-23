ALTER TABLE llm_provider_config
  ADD COLUMN supports_rerank BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE llm_provider_config
  ADD COLUMN rerank_model VARCHAR(128);

ALTER TABLE llm_provider_config
  ADD COLUMN rerank_workspace_id VARCHAR(128);
