package interview.guide.modules.voiceinterview.handler;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.entity.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 负责语音面试开场题目策略、开场音频缓存和首次连接下发。
 */
@Slf4j
final class VoiceWebSocketOpeningService {

    private static final String DEFAULT_OPENING_QUESTION_ALGORITHM =
        "你好，我是本场面试官。第一个问题：请你口述一道算法题，不写代码，只讲\u300C问题建模、数据结构选型、步骤、复杂度、边界处理\u300D。";
    private static final String DEFAULT_OPENING_QUESTION_BACKEND =
        "你好，我是本场面试官。第一个问题：请用 1 分钟介绍一个你深度参与的项目，按三点回答：业务目标、你负责的核心模块、核心技术栈。说完我会立刻追问一个关键技术决策。";

    private final QwenTtsService ttsService;
    private final VoiceInterviewProperties properties;
    private final VoiceWebSocketConversationService conversationService;
    private final VoiceWebSocketMessageService messageService;
    private final Function<byte[], byte[]> pcmToWav;
    private final Map<String, byte[]> openingAudioCache = new ConcurrentHashMap<>();

    VoiceWebSocketOpeningService(
        QwenTtsService ttsService,
        VoiceInterviewProperties properties,
        VoiceWebSocketConversationService conversationService,
        VoiceWebSocketMessageService messageService,
        Function<byte[], byte[]> pcmToWav) {
        this.ttsService = ttsService;
        this.properties = properties;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.pcmToWav = pcmToWav;
    }

    void warmupOpeningAudioCache() {
        if (!properties.isOpeningAudioWarmupEnabled()) {
            log.info("Opening audio cache warmup is disabled");
            return;
        }
        try {
            VoiceInterviewProperties.OpeningConfig opening = properties.getOpening();
            if (opening == null) {
                return;
            }
            LinkedHashSet<String> allTemplates = new LinkedHashSet<>();
            if (opening.getSkillQuestions() != null) {
                allTemplates.addAll(opening.getSkillQuestions().values());
            }
            allTemplates.add(opening.getAlgorithmQuestion());
            allTemplates.add(opening.getBackendQuestion());
            for (String template : allTemplates) {
                preloadOpeningAudio(template);
            }
            log.info("Opening audio cache warmed: {} entries", openingAudioCache.size());
        } catch (Exception e) {
            log.warn("Opening audio cache warmup skipped: {}", e.getMessage());
        }
    }

    void sendOpeningQuestion(String sessionId, WebSocketSession session) {
        try {
            if (session == null || !session.isOpen()) {
                return;
            }

            VoiceInterviewSessionEntity sessionEntity = conversationService.getSessionEntity(sessionId);
            if (sessionEntity == null) {
                log.warn("Session entity not found when sending opening question: {}", sessionId);
                return;
            }

            List<String> history = conversationService.getHistory(sessionId, sessionEntity.getLlmProvider());
            if (history != null && !history.isEmpty()) {
                return;
            }

            String aiReply = buildOpeningQuestion(sessionEntity);
            if (aiReply == null || aiReply.isBlank() || !session.isOpen()) {
                return;
            }

            // 先落库再推前端，确保用户提交时 DB 中已有该条消息
            conversationService.saveMessage(sessionId, null, aiReply);
            messageService.sendTextMessage(session, aiReply, true);

            byte[] wavAudio = getOpeningWavAudio(aiReply);
            if (wavAudio.length > 0 && session.isOpen()) {
                messageService.sendAudio(session, wavAudio, aiReply);
            }

            log.info("Opening question sent for session {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to send opening question for session {}", sessionId, e);
        }
    }

    private void preloadOpeningAudio(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        byte[] wavAudio = synthesizeToWav(text);
        if (wavAudio.length > 0) {
            openingAudioCache.put(text, wavAudio);
        }
    }

    private byte[] getOpeningWavAudio(String text) {
        byte[] cached = openingAudioCache.get(text);
        if (cached != null && cached.length > 0) {
            return cached;
        }
        byte[] wav = synthesizeToWav(text);
        if (wav.length > 0) {
            openingAudioCache.put(text, wav);
        }
        return wav;
    }

    private byte[] synthesizeToWav(String text) {
        byte[] pcm = ttsService.synthesize(text);
        if (pcm == null || pcm.length == 0) {
            return new byte[0];
        }
        return pcmToWav.apply(pcm);
    }

    private String buildOpeningQuestion(VoiceInterviewSessionEntity sessionEntity) {
        String skillId = sessionEntity.getSkillId() != null ? sessionEntity.getSkillId() : "";
        VoiceInterviewProperties.OpeningConfig opening = properties.getOpening();
        Map<String, String> skillQuestions = opening != null ? opening.getSkillQuestions() : null;
        if (skillQuestions != null) {
            String bySkill = skillQuestions.get(skillId);
            if (bySkill != null && !bySkill.isBlank()) {
                return bySkill;
            }
        }
        List<String> algorithmSkills = opening != null && opening.getAlgorithmSkills() != null
            ? opening.getAlgorithmSkills()
            : List.of();

        if (algorithmSkills.contains(skillId)) {
            String configured = opening != null ? opening.getAlgorithmQuestion() : null;
            return configured != null && !configured.isBlank()
                ? configured
                : DEFAULT_OPENING_QUESTION_ALGORITHM;
        }
        String configured = opening != null ? opening.getBackendQuestion() : null;
        return configured != null && !configured.isBlank()
            ? configured
            : DEFAULT_OPENING_QUESTION_BACKEND;
    }
}
