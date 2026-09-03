-- P1-06: 语音上下文摘要显式覆盖边界（仅 SUMMARY 行使用，nullable，旧数据由一次性迁移写入）
-- 索引按热路径查询建：session_id + sequence_num（查询侧排除 SUMMARY，不写部分索引）
ALTER TABLE voice_interview_messages ADD COLUMN summary_covered_sequence_num INTEGER;
CREATE INDEX idx_voice_messages_session_seq ON voice_interview_messages (session_id, sequence_num);
