-- 为 PROCESSING 任务增加执行代次 fencing token，阻止超时回收后的旧消费者写入终态。
ALTER TABLE knowledge_bases ADD COLUMN vector_attempt_id VARCHAR(36);
ALTER TABLE resumes ADD COLUMN analyze_attempt_id VARCHAR(36);
