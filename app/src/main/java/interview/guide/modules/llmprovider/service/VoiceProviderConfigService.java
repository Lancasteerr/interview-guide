package interview.guide.modules.llmprovider.service;

import interview.guide.common.config.YamlTextEditor;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.AsrConfigDTO;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.TtsConfigDTO;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语音面试 ASR/TTS 配置的读取、持久化、连通性检查和运行时刷新。
 */
@Slf4j
final class VoiceProviderConfigService {

  private final VoiceInterviewProperties voiceProperties;
  private final QwenAsrService asrService;
  private final QwenTtsService ttsService;
  private final String yamlPath;
  private final String envPath;
  private final Function<String, String> apiKeyMasker;

  VoiceProviderConfigService(
      VoiceInterviewProperties voiceProperties,
      QwenAsrService asrService,
      QwenTtsService ttsService,
      String yamlPath,
      String envPath,
      Function<String, String> apiKeyMasker) {
    this.voiceProperties = voiceProperties;
    this.asrService = asrService;
    this.ttsService = ttsService;
    this.yamlPath = yamlPath;
    this.envPath = envPath;
    this.apiKeyMasker = apiKeyMasker;
  }

  AsrConfigDTO getAsrConfig() {
    VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
    return AsrConfigDTO.builder()
        .url(asr.getUrl())
        .model(asr.getModel())
        .maskedApiKey(apiKeyMasker.apply(asr.getApiKey()))
        .language(asr.getLanguage())
        .format(asr.getFormat())
        .sampleRate(asr.getSampleRate())
        .enableTurnDetection(asr.isEnableTurnDetection())
        .turnDetectionType(asr.getTurnDetectionType())
        .turnDetectionThreshold(asr.getTurnDetectionThreshold())
        .turnDetectionSilenceDurationMs(asr.getTurnDetectionSilenceDurationMs())
        .build();
  }

  TtsConfigDTO getTtsConfig() {
    VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
    return TtsConfigDTO.builder()
        .model(tts.getModel())
        .maskedApiKey(apiKeyMasker.apply(tts.getApiKey()))
        .voice(tts.getVoice())
        .format(tts.getFormat())
        .sampleRate(tts.getSampleRate())
        .mode(tts.getMode())
        .languageType(tts.getLanguageType())
        .speechRate(tts.getSpeechRate())
        .volume(tts.getVolume())
        .build();
  }

  ProviderTestResult testAsrConfig() {
    VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
    try {
      URI wsUri = URI.create(asr.getUrl());
      String host = wsUri.getHost();
      int port = wsUri.getPort() > 0 ? wsUri.getPort()
          : (wsUri.getScheme().equals("wss") ? 443 : 80);
      InetSocketAddress address = new InetSocketAddress(host, port);
      Socket socket = new Socket();
      socket.connect(address, 5000);
      socket.close();
      return ProviderTestResult.builder()
          .success(true)
          .message("ASR 服务网络端口可达: " + host + "；尚未验证 API Key、模型权限及语音识别能力")
          .model(asr.getModel())
          .build();
    } catch (Exception e) {
      return ProviderTestResult.builder()
          .success(false)
          .message("ASR 连接失败: " + e.getMessage())
          .model(asr.getModel())
          .build();
    }
  }

  void updateAsrConfig(AsrConfigRequest request) {
    VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
    VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
    if (request.url() != null) asr.setUrl(request.url());
    if (request.model() != null) asr.setModel(request.model());
    if (request.language() != null) asr.setLanguage(request.language());
    if (request.format() != null) asr.setFormat(request.format());
    if (request.sampleRate() != null) asr.setSampleRate(request.sampleRate());
    if (request.enableTurnDetection() != null) asr.setEnableTurnDetection(request.enableTurnDetection());
    if (request.turnDetectionType() != null) asr.setTurnDetectionType(request.turnDetectionType());
    if (request.turnDetectionThreshold() != null) asr.setTurnDetectionThreshold(request.turnDetectionThreshold());
    if (request.turnDetectionSilenceDurationMs() != null) {
      asr.setTurnDetectionSilenceDurationMs(request.turnDetectionSilenceDurationMs());
    }
    if (request.apiKey() != null) {
      asr.setApiKey(request.apiKey());
      tts.setApiKey(request.apiKey());
      updateEnvValue("AI_BAILIAN_API_KEY", request.apiKey());
    }

    writeAsrConfigToYaml(asr);
    asrService.reload(voiceProperties);
    if (request.apiKey() != null) {
      ttsService.reload(voiceProperties);
    }
    log.info("Updated ASR config");
  }

  void updateTtsConfig(TtsConfigRequest request) {
    VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
    VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
    if (request.model() != null) tts.setModel(request.model());
    if (request.voice() != null) tts.setVoice(request.voice());
    if (request.format() != null) tts.setFormat(request.format());
    if (request.sampleRate() != null) tts.setSampleRate(request.sampleRate());
    if (request.mode() != null) tts.setMode(request.mode());
    if (request.languageType() != null) tts.setLanguageType(request.languageType());
    if (request.speechRate() != null) tts.setSpeechRate(request.speechRate());
    if (request.volume() != null) tts.setVolume(request.volume());
    if (request.apiKey() != null) {
      tts.setApiKey(request.apiKey());
      asr.setApiKey(request.apiKey());
      updateEnvValue("AI_BAILIAN_API_KEY", request.apiKey());
    }

    writeTtsConfigToYaml(tts);
    ttsService.reload(voiceProperties);
    if (request.apiKey() != null) {
      asrService.reload(voiceProperties);
    }
    log.info("Updated TTS config");
  }

  private void writeAsrConfigToYaml(VoiceInterviewProperties.AsrConfig asr) {
    mutateYamlText(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入 ASR 配置失败", editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("url", asr.getUrl());
      values.put("model", asr.getModel());
      values.put("api-key", "${AI_BAILIAN_API_KEY}");
      values.put("language", asr.getLanguage());
      values.put("format", asr.getFormat());
      values.put("sample-rate", asr.getSampleRate());
      values.put("enable-turn-detection", asr.isEnableTurnDetection());
      values.put("turn-detection-type", asr.getTurnDetectionType());
      values.put("turn-detection-threshold", asr.getTurnDetectionThreshold());
      values.put("turn-detection-silence-duration-ms", asr.getTurnDetectionSilenceDurationMs());
      editor.setBlock(new String[]{"app", "voice-interview", "qwen"}, "asr", values);
    });
  }

  private void writeTtsConfigToYaml(VoiceInterviewProperties.QwenTtsConfig tts) {
    mutateYamlText(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入 TTS 配置失败", editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("model", tts.getModel());
      values.put("api-key", "${AI_BAILIAN_API_KEY}");
      values.put("voice", tts.getVoice());
      values.put("format", tts.getFormat());
      values.put("sample-rate", tts.getSampleRate());
      values.put("mode", tts.getMode());
      values.put("language-type", tts.getLanguageType());
      values.put("speech-rate", tts.getSpeechRate());
      values.put("volume", tts.getVolume());
      editor.setBlock(new String[]{"app", "voice-interview", "qwen"}, "tts", values);
    });
  }

  private void mutateYamlText(
      ErrorCode errorCode, String errorMessage, Consumer<YamlTextEditor> mutator) {
    if (yamlPath == null || yamlPath.isBlank()) {
      log.warn("YAML path not configured, skip writing");
      return;
    }
    try {
      Path path = Path.of(yamlPath);
      List<String> lines = Files.exists(path)
          ? new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8))
          : new ArrayList<>();
      YamlTextEditor editor = new YamlTextEditor(lines);
      mutator.accept(editor);
      String content = String.join("\n", editor.getLines());
      if (!content.endsWith("\n")) {
        content += "\n";
      }
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new BusinessException(errorCode, errorMessage + ": " + e.getMessage());
    }
  }

  private void updateEnvValue(String key, String value) {
    if (envPath == null || envPath.isBlank()) return;
    try {
      Path path = Path.of(envPath);
      if (!Files.exists(path)) {
        Files.writeString(path, key + "=" + value + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return;
      }
      String content = Files.readString(path, StandardCharsets.UTF_8);
      if (content.contains(key + "=")) {
        content = content.replaceAll("(?m)^" + Pattern.quote(key) + "=.*",
            Matcher.quoteReplacement(key + "=" + value));
      } else {
        if (!content.endsWith("\n")) {
          content += "\n";
        }
        content += key + "=" + value + "\n";
      }
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.warn("写入 .env 失败: {}", e.getMessage());
    }
  }
}
