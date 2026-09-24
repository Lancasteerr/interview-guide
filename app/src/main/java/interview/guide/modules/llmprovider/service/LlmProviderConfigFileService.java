package interview.guide.modules.llmprovider.service;

import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.config.YamlTextEditor;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责 Provider YAML 和 .env 文件的兼容写入，保留文本格式与环境变量引用。
 */
@Slf4j
final class LlmProviderConfigFileService {

  private final String yamlPath;
  private final String envPath;

  LlmProviderConfigFileService(String yamlPath, String envPath) {
    this.yamlPath = yamlPath;
    this.envPath = envPath;
  }

  void validateWritablePaths() {
    ensureParentWritable(yamlPath, "config-yaml-path");
    ensureParentWritable(envPath, "config-env-path");
  }

  void writeProviderToYaml(String id, ProviderConfig config, String envKey) {
    mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "写入 YAML 配置失败", editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("base-url", config.getBaseUrl());
      values.put("api-key", "${" + envKey + "}");
      values.put("model", config.getModel());
      if (config.getEmbeddingModel() != null) {
        values.put("embedding-model", config.getEmbeddingModel());
      }
      if (config.getEmbeddingDimensions() != null) {
        values.put("embedding-dimensions", config.getEmbeddingDimensions());
      }
      values.put("supports-rerank", Boolean.TRUE.equals(config.getSupportsRerank()));
      if (Boolean.TRUE.equals(config.getSupportsRerank())) {
        values.put("rerank-model", config.getRerankModel());
        values.put("rerank-workspace-id", config.getRerankWorkspaceId());
      }
      if (config.getTemperature() != null) {
        values.put("temperature", config.getTemperature());
      }
      editor.setBlock(new String[]{"app", "ai", "providers"}, id, values);
      if (!Boolean.TRUE.equals(config.getSupportsRerank())) {
        editor.removeBlockKeys(
            new String[]{"app", "ai", "providers"}, id,
            "rerank-model", "rerank-workspace-id");
      }
    });
  }

  void removeProviderFromYaml(String id) {
    mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "删除 YAML 配置失败",
        editor -> editor.removeSection(new String[]{"app", "ai", "providers"}, id));
  }

  void writeDefaultProviderToYaml(String defaultProvider) {
    mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "写入默认 Provider 配置失败", editor -> {
      editor.setScalar(new String[]{"app", "ai", "default-provider"}, defaultProvider);
      editor.removeSection(new String[]{"app", "ai"}, "module-defaults");
    });
  }

  void writeEnvValue(String key, String value) {
    if (envPath == null || envPath.isBlank()) {
      return;
    }
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

  void updateEnvValue(String key, String value) {
    writeEnvValue(key, value);
  }

  void removeFromEnv(String key) {
    if (envPath == null || envPath.isBlank()) {
      return;
    }
    try {
      Path path = Path.of(envPath);
      if (!Files.exists(path)) {
        return;
      }
      String content = Files.readString(path, StandardCharsets.UTF_8);
      content = content.replaceAll("(?m)^" + Pattern.quote(key) + "=.*\\R?", "");
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.warn("删除 .env 条目失败: {}", e.getMessage());
    }
  }

  private void ensureParentWritable(String rawPath, String label) {
    if (rawPath == null || rawPath.isBlank()) {
      log.warn("{} is not configured; runtime Provider edits will be skipped", label);
      return;
    }
    Path parent = Path.of(rawPath).toAbsolutePath().getParent();
    if (parent == null) {
      return;
    }
    try {
      Files.createDirectories(parent);
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          label + " 的父目录不可创建: " + parent, e);
    }
    if (!Files.isWritable(parent)) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          label + " 的父目录不可写: " + parent);
    }
    log.info("{} resolved to {} (parent writable)", label, rawPath);
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
}
