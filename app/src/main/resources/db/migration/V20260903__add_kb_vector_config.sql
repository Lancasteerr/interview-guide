-- P1-03: 知识库保存向量化 Chunk 策略快照与完成时间（均 nullable，旧数据视为旧版默认 800）
ALTER TABLE knowledge_bases ADD COLUMN vector_config TEXT;
ALTER TABLE knowledge_bases ADD COLUMN vectorized_at TIMESTAMP;
