-- P1-07: 卡住任务恢复所需的进度时间戳与恢复计数
-- 语义：*_updated_at = 最后一次有效进展时间（状态变化与心跳都推进），不是进入当前状态的时间
ALTER TABLE knowledge_bases ADD COLUMN vector_updated_at TIMESTAMP;
ALTER TABLE knowledge_bases ADD COLUMN vector_recovery_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE resumes ADD COLUMN analyze_updated_at TIMESTAMP;
ALTER TABLE resumes ADD COLUMN analyze_recovery_count INTEGER NOT NULL DEFAULT 0;

-- 仅回填非终态（PENDING/PROCESSING）为 NOW()；旧的 PENDING/PROCESSING 若回填上传时间会被立即判 stale 误补投
UPDATE knowledge_bases SET vector_updated_at = NOW() WHERE vector_status IN ('PENDING', 'PROCESSING');
UPDATE resumes SET analyze_updated_at = NOW() WHERE analyze_status IN ('PENDING', 'PROCESSING');

CREATE INDEX idx_kb_vector_status_updated ON knowledge_bases (vector_status, vector_updated_at);
CREATE INDEX idx_resume_analyze_status_updated ON resumes (analyze_status, analyze_updated_at);
