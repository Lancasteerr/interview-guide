package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;

/**
 * Qwen 实时 TTS 的运行时配置。
 */
final class QwenTtsConfiguration {

  private String model;
  private String apiKey;
  private String voice;
  private String format;
  private Integer sampleRate;
  private String mode;
  private String languageType;
  private Float speechRate;
  private Integer volume;
  private int connectTimeoutSeconds;

  QwenTtsConfiguration(VoiceInterviewProperties properties) {
    reload(properties);
  }

  void reload(VoiceInterviewProperties properties) {
    VoiceInterviewProperties.QwenTtsConfig tts = properties.getQwen().getTts();
    this.model = tts.getModel();
    this.apiKey = tts.getApiKey();
    this.voice = tts.getVoice();
    this.format = tts.getFormat();
    this.sampleRate = tts.getSampleRate();
    this.mode = tts.getMode();
    this.languageType = tts.getLanguageType();
    this.speechRate = tts.getSpeechRate();
    this.volume = tts.getVolume();
    this.connectTimeoutSeconds = Math.max(1, properties.getTtsConnectTimeoutSeconds());
  }

  boolean hasApiKey() {
    return apiKey != null && !apiKey.trim().isEmpty();
  }

  String model() {
    return model;
  }

  String apiKey() {
    return apiKey;
  }

  String voice() {
    return voice;
  }

  String format() {
    return format;
  }

  Integer sampleRate() {
    return sampleRate;
  }

  String mode() {
    return mode;
  }

  String languageType() {
    return languageType;
  }

  Float speechRate() {
    return speechRate;
  }

  Integer volume() {
    return volume;
  }

  int connectTimeoutSeconds() {
    return connectTimeoutSeconds;
  }

  void setModel(String model) {
    this.model = model;
  }

  void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  void setVoice(String voice) {
    this.voice = voice;
  }

  void setFormat(String format) {
    this.format = format;
  }

  void setSampleRate(Integer sampleRate) {
    this.sampleRate = sampleRate;
  }

  void setMode(String mode) {
    this.mode = mode;
  }

  void setLanguageType(String languageType) {
    this.languageType = languageType;
  }

  void setSpeechRate(Float speechRate) {
    this.speechRate = speechRate;
  }

  void setVolume(Integer volume) {
    this.volume = volume;
  }
}
