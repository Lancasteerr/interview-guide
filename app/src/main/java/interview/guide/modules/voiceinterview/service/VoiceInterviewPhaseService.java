package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.entity.VoiceInterviewSessionEntity;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 语音面试阶段规则：首阶段选择、阶段转换和阶段阈值判断。
 */
@Slf4j
final class VoiceInterviewPhaseService {

  private final VoiceInterviewProperties properties;

  VoiceInterviewPhaseService(VoiceInterviewProperties properties) {
    this.properties = properties;
  }

  VoiceInterviewSessionEntity.InterviewPhase determineFirstPhase(CreateSessionRequest request) {
    if (request.getIntroEnabled()) {
      return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
    }
    if (request.getTechEnabled()) {
      return VoiceInterviewSessionEntity.InterviewPhase.TECH;
    }
    if (request.getProjectEnabled()) {
      return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
    }
    if (request.getHrEnabled()) {
      return VoiceInterviewSessionEntity.InterviewPhase.HR;
    }
    return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
  }

  boolean shouldTransitionToNextPhase(
      VoiceInterviewSessionEntity session,
      LocalDateTime phaseStartTime,
      int questionCount) {
    VoiceInterviewSessionEntity.InterviewPhase currentPhase = session.getCurrentPhase();
    if (currentPhase == null || currentPhase == VoiceInterviewSessionEntity.InterviewPhase.COMPLETED) {
      return false;
    }

    Duration phaseDuration = Duration.between(phaseStartTime, LocalDateTime.now());
    VoiceInterviewProperties.DurationConfig config = getPhaseConfig(currentPhase);
    if (phaseDuration.toMinutes() >= config.getMaxDuration()) {
      log.info("Phase {} reached max duration {} minutes, forcing transition",
          currentPhase, config.getMaxDuration());
      return true;
    }
    if (questionCount >= config.getMaxQuestions()) {
      log.info("Phase {} reached max questions {}, suggesting transition",
          currentPhase, config.getMaxQuestions());
      return true;
    }
    if (phaseDuration.toMinutes() >= config.getSuggestedDuration()
        && questionCount >= config.getMinQuestions()) {
      log.info("Phase {} reached suggested duration {} with {} questions, suggesting transition",
          currentPhase, config.getSuggestedDuration(), questionCount);
      return true;
    }
    return false;
  }

  VoiceInterviewSessionEntity.InterviewPhase getNextPhase(VoiceInterviewSessionEntity session) {
    VoiceInterviewSessionEntity.InterviewPhase current = session.getCurrentPhase();
    if (current == null) {
      return getFirstEnabledPhase(session);
    }
    return switch (current) {
      case INTRO -> session.getTechEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.TECH
          : session.getProjectEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT
          : session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR
          : VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
      case TECH -> session.getProjectEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT
          : session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR
          : VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
      case PROJECT -> session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR
          : VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
      case HR, COMPLETED -> VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    };
  }

  private VoiceInterviewSessionEntity.InterviewPhase getFirstEnabledPhase(
      VoiceInterviewSessionEntity session) {
    if (session.getIntroEnabled()) {
      return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
    }
    if (session.getTechEnabled()) {
      return VoiceInterviewSessionEntity.InterviewPhase.TECH;
    }
    if (session.getProjectEnabled()) {
      return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
    }
    if (session.getHrEnabled()) {
      return VoiceInterviewSessionEntity.InterviewPhase.HR;
    }
    return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
  }

  private VoiceInterviewProperties.DurationConfig getPhaseConfig(
      VoiceInterviewSessionEntity.InterviewPhase phase) {
    return switch (phase) {
      case INTRO -> properties.getPhase().getIntro();
      case TECH -> properties.getPhase().getTech();
      case PROJECT -> properties.getPhase().getProject();
      case HR -> properties.getPhase().getHr();
      default -> new VoiceInterviewProperties.DurationConfig(0, 0, 0, 0, 0);
    };
  }
}
