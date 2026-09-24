package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;

/**
 * Qwen 实时 ASR 的运行时配置。
 *
 * <p>将配置刷新与连接生命周期分开，避免 ASR 会话服务同时承担配置状态管理。</p>
 */
final class QwenAsrConfiguration {

  private String url;
  private String model;
  private String apiKey;
  private String language;
  private String format;
  private Integer sampleRate;
  private Boolean enableTurnDetection;
  private String turnDetectionType;
  private Float turnDetectionThreshold;
  private Integer turnDetectionSilenceDurationMs;

  QwenAsrConfiguration(VoiceInterviewProperties.AsrConfig config) {
    reload(config);
  }

  void reload(VoiceInterviewProperties.AsrConfig config) {
    this.url = config.getUrl();
    this.model = config.getModel();
    this.apiKey = config.getApiKey();
    this.language = config.getLanguage();
    this.format = config.getFormat();
    this.sampleRate = config.getSampleRate();
    this.enableTurnDetection = config.isEnableTurnDetection();
    this.turnDetectionType = config.getTurnDetectionType();
    this.turnDetectionThreshold = config.getTurnDetectionThreshold();
    this.turnDetectionSilenceDurationMs = config.getTurnDetectionSilenceDurationMs();
  }

  boolean hasApiKey() {
    return apiKey != null && !apiKey.trim().isEmpty();
  }

  String url() {
    return url;
  }

  String model() {
    return model;
  }

  String apiKey() {
    return apiKey;
  }

  String language() {
    return language;
  }

  String format() {
    return format;
  }

  Integer sampleRate() {
    return sampleRate;
  }

  Boolean enableTurnDetection() {
    return enableTurnDetection;
  }

  String turnDetectionType() {
    return turnDetectionType;
  }

  Float turnDetectionThreshold() {
    return turnDetectionThreshold;
  }

  Integer turnDetectionSilenceDurationMs() {
    return turnDetectionSilenceDurationMs;
  }

  void setUrl(String url) {
    this.url = url;
  }

  void setModel(String model) {
    this.model = model;
  }

  void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  void setLanguage(String language) {
    this.language = language;
  }

  void setFormat(String format) {
    this.format = format;
  }

  void setSampleRate(Integer sampleRate) {
    this.sampleRate = sampleRate;
  }

  void setEnableTurnDetection(Boolean enableTurnDetection) {
    this.enableTurnDetection = enableTurnDetection;
  }

  void setTurnDetectionType(String turnDetectionType) {
    this.turnDetectionType = turnDetectionType;
  }

  void setTurnDetectionThreshold(Float turnDetectionThreshold) {
    this.turnDetectionThreshold = turnDetectionThreshold;
  }

  void setTurnDetectionSilenceDurationMs(Integer turnDetectionSilenceDurationMs) {
    this.turnDetectionSilenceDurationMs = turnDetectionSilenceDurationMs;
  }
}
