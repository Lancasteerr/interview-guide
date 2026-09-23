package interview.guide.modules.voiceinterview.repository;

import interview.guide.modules.voiceinterview.entity.VoiceInterviewMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 语音面试消息Repository
 */
@Repository
public interface VoiceInterviewMessageRepository extends JpaRepository<VoiceInterviewMessageEntity, Long> {

    /**
     * 根据会话ID查找所有消息，按序号升序排列
     */
    List<VoiceInterviewMessageEntity> findBySessionIdOrderBySequenceNumAsc(Long sessionId);

    List<VoiceInterviewMessageEntity> findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
        Long sessionId, String messageType);

    /**
     * 倒序分页读取最近窗口的非 SUMMARY 消息（P1-06 有界加载）。
     */
    List<VoiceInterviewMessageEntity> findBySessionIdAndMessageTypeNotOrderBySequenceNumDesc(
        Long sessionId, String messageType, Pageable pageable);

    /**
     * 按 sequenceNum 范围升序读取待摘要消息（排除 SUMMARY），Pageable 限制单次摘要批量。
     */
    List<VoiceInterviewMessageEntity> findBySessionIdAndMessageTypeNotAndSequenceNumGreaterThanAndSequenceNumLessThanOrderBySequenceNumAsc(
        Long sessionId, String messageType, Integer sequenceNumAfter, Integer sequenceNumBefore,
        Pageable pageable);

    /**
     * 定位排除 SUMMARY 后第 N 条消息（OFFSET/LIMIT 1），用于旧 SUMMARY 行覆盖轮数到边界的迁移。
     */
    List<VoiceInterviewMessageEntity> findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
        Long sessionId, String messageType, Pageable pageable);

    Optional<VoiceInterviewMessageEntity>
        findFirstBySessionIdAndUserRecognizedTextIsNullAndAiGeneratedTextIsNotNullOrderBySequenceNumDesc(
            Long sessionId);

    long countBySessionId(Long sessionId);

    long countBySessionIdAndMessageTypeNot(Long sessionId, String messageType);

    void deleteBySessionId(Long sessionId);

    Optional<VoiceInterviewMessageEntity> findFirstBySessionIdAndMessageTypeOrderBySequenceNumAsc(
        Long sessionId, String messageType);

}
