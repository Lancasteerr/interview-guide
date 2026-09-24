package interview.guide.modules.voiceinterview.handler;

import interview.guide.modules.voiceinterview.context.VoiceHistoryLoader;
import interview.guide.modules.voiceinterview.entity.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 负责 WebSocket 语音回合所需的会话读取、历史加载和消息持久化。
 */
@Component
@Slf4j
final class VoiceWebSocketConversationService {

    private final VoiceInterviewService interviewService;
    private final VoiceHistoryLoader voiceHistoryLoader;

    VoiceWebSocketConversationService(
        VoiceInterviewService interviewService,
        VoiceHistoryLoader voiceHistoryLoader) {
        this.interviewService = interviewService;
        this.voiceHistoryLoader = voiceHistoryLoader;
    }

    List<String> getHistory(String sessionId, String llmProvider) {
        try {
            List<String> history = voiceHistoryLoader.loadHistory(sessionId, llmProvider);
            log.debug("Loaded {} compressed history entries for session {}", history.size(), sessionId);
            return history;
        } catch (Exception e) {
            log.error("Error loading conversation history for session {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    VoiceInterviewSessionEntity getSessionEntity(String sessionId) {
        try {
            Long sessionIdLong = Long.parseLong(sessionId);
            return interviewService.getSession(sessionIdLong);
        } catch (NumberFormatException e) {
            log.error("Invalid session ID format: {}", sessionId);
            return null;
        }
    }

    void saveMessage(String sessionId, String userText, String aiText) {
        try {
            interviewService.saveMessage(sessionId, userText, aiText);
            log.debug("Message saved to database for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Error saving message for session {}", sessionId, e);
        }
    }
}
