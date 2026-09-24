package interview.guide.modules.interview.service;

import interview.guide.modules.interview.config.InterviewSkillProperties;
import interview.guide.modules.interview.service.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.service.InterviewSkillService.SkillDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 面试 Skill references 适配器，负责索引、缓存、路径解析和参考内容组装。
 */
@Slf4j
final class InterviewSkillReferenceService {

  private static final int MAX_REFERENCE_SECTION_CHARS = 12000;
  private static final int MAX_EVALUATION_REFERENCE_SECTION_CHARS = 6000;
  private static final int MAX_SINGLE_REFERENCE_CHARS = 3000;

  private final ResourceLoader resourceLoader;
  private final Map<String, InterviewSkillProperties.SkillDefinition> presetRegistry;
  private final Map<String, String> referenceCache = new ConcurrentHashMap<>();
  private final Map<String, RefMapping> categoryRefIndex = new HashMap<>();

  InterviewSkillReferenceService(ResourceLoader resourceLoader,
      Map<String, InterviewSkillProperties.SkillDefinition> presetRegistry) {
    this.resourceLoader = resourceLoader;
    this.presetRegistry = presetRegistry;
  }

  void rebuildIndex() {
    categoryRefIndex.clear();
    for (var entry : presetRegistry.entrySet()) {
      InterviewSkillProperties.SkillDefinition definition = entry.getValue();
      if (definition.getCategories() == null) {
        continue;
      }
      for (InterviewSkillProperties.CategoryDef category : definition.getCategories()) {
        if (category.getRef() != null && !category.getRef().isBlank() && category.getKey() != null) {
          categoryRefIndex.putIfAbsent(category.getKey(), new RefMapping(
              category.getRef(), Boolean.TRUE.equals(category.getShared()), entry.getKey()));
        }
      }
    }
    log.info("构建 category→reference 映射: {} 个条目", categoryRefIndex.size());
  }

  RefMapping findMapping(String categoryKey) {
    return categoryRefIndex.get(categoryKey);
  }

  String buildReferenceFileList() {
    Map<String, String> refDescriptions = new LinkedHashMap<>();
    for (var entry : presetRegistry.entrySet()) {
      InterviewSkillProperties.SkillDefinition definition = entry.getValue();
      String skillName = definition.getDisplayName() != null
          ? definition.getDisplayName() : definition.getName();
      if (definition.getCategories() == null) {
        continue;
      }
      for (InterviewSkillProperties.CategoryDef category : definition.getCategories()) {
        if (category.getRef() != null && !category.getRef().isBlank()) {
          refDescriptions.putIfAbsent(category.getRef(),
              "| " + category.getRef()
                  + " | " + (Boolean.TRUE.equals(category.getShared()) ? "shared" : "skill-local")
                  + " | " + skillName
                  + " | " + category.getLabel() + " |\n");
        }
      }
    }

    if (refDescriptions.isEmpty()) {
      return "（无可用参考文件）";
    }

    StringBuilder result = new StringBuilder("| 文件名 | 范围 | 来源 Skill | 覆盖内容 |\n");
    result.append("|--------|------|-------------|----------|\n");
    refDescriptions.values().forEach(result::append);
    return result.toString();
  }

  String buildReferenceSection(SkillDTO skill, Map<String, Integer> allocation) {
    return buildReferenceSectionInternal(
        skill,
        category -> allocation.getOrDefault(category.key(), 0) > 0,
        MAX_REFERENCE_SECTION_CHARS);
  }

  String buildEvaluationReferenceSection(String skillId,
      java.util.function.Function<String, SkillDTO> skillLoader) {
    SkillDTO skill = skillLoader.apply(skillId);
    return buildReferenceSectionInternal(skill, category -> true,
        MAX_EVALUATION_REFERENCE_SECTION_CHARS);
  }

  String buildEvaluationReferenceSectionSafe(String skillId,
      java.util.function.Function<String, SkillDTO> skillLoader) {
    if (skillId == null || skillId.isBlank()) {
      return "";
    }
    try {
      return buildEvaluationReferenceSection(skillId, skillLoader);
    } catch (Exception e) {
      log.warn("加载评估参考基线失败，降级为无参考: skillId={}, error={}", skillId, e.getMessage());
      return "";
    }
  }

  private String buildReferenceSectionInternal(SkillDTO skill,
      Predicate<SkillCategoryDTO> categoryFilter, int maxChars) {
    StringBuilder result = new StringBuilder();
    for (SkillCategoryDTO category : skill.categories()) {
      if (!categoryFilter.test(category)
          || category.ref() == null || category.ref().isBlank()) {
        continue;
      }

      String effectiveSkillId = skill.id();
      if (InterviewSkillService.CUSTOM_SKILL_ID.equals(skill.id())
          && !category.shared() && category.ref() != null) {
        RefMapping mapping = categoryRefIndex.get(category.key());
        if (mapping != null) {
          effectiveSkillId = mapping.sourceSkillId();
        }
      }

      String referenceContent = loadReferenceContent(effectiveSkillId, category.ref(), category.shared());
      if (referenceContent.isBlank()) {
        continue;
      }
      if (!result.isEmpty()) {
        result.append("\n\n");
      }
      result.append("### ").append(category.label()).append(" (").append(category.key()).append(")\n");
      result.append(referenceContent);
      if (result.length() >= maxChars) {
        result.setLength(maxChars);
        result.append("\n...（references 已截断）");
        break;
      }
    }
    return result.isEmpty() ? "未配置 references。" : result.toString();
  }

  private String loadReferenceContent(String skillId, String referenceFile, boolean shared) {
    if (!isSafeReferencePath(referenceFile)) {
      log.warn("忽略不安全的 reference 路径: skillId={}, ref={}", skillId, referenceFile);
      return "";
    }

    List<String> candidateLocations = resolveReferenceLocations(skillId, referenceFile, shared);
    for (String location : candidateLocations) {
      String content = referenceCache.computeIfAbsent(location, this::readReferenceContent);
      if (!content.isBlank()) {
        return content;
      }
    }
    log.warn("未找到 reference: skillId={}, ref={}, shared={}, locations={}",
        skillId, referenceFile, shared, candidateLocations);
    return "";
  }

  private List<String> resolveReferenceLocations(String skillId, String referenceFile, boolean shared) {
    LinkedHashSet<String> locations = new LinkedHashSet<>();
    if (shared) {
      locations.add(buildSharedReferenceLocation(referenceFile));
    }
    addSkillReferenceLocations(locations, skillId, referenceFile);
    if (!shared) {
      locations.add(buildSharedReferenceLocation(referenceFile));
    }
    if (InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId) || shared) {
      for (String presetSkillId : presetRegistry.keySet()) {
        addSkillReferenceLocations(locations, presetSkillId, referenceFile);
      }
    }
    return List.copyOf(locations);
  }

  private void addSkillReferenceLocations(LinkedHashSet<String> locations,
      String skillId, String referenceFile) {
    if (skillId == null || skillId.isBlank()
        || InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId)) {
      return;
    }
    locations.add("classpath:skills/" + skillId + "/references/" + referenceFile);
    locations.add("classpath:skills/" + skillId + "/" + referenceFile);
  }

  private String buildSharedReferenceLocation(String referenceFile) {
    return "classpath:skills/_shared/references/" + referenceFile;
  }

  private String readReferenceContent(String location) {
    Resource resource = resourceLoader.getResource(location);
    if (!resource.exists()) {
      return "";
    }
    try {
      String content = resource.getContentAsString(StandardCharsets.UTF_8).trim();
      if (content.length() > MAX_SINGLE_REFERENCE_CHARS) {
        return content.substring(0, MAX_SINGLE_REFERENCE_CHARS) + "\n...（单文件内容已截断）";
      }
      return content;
    } catch (IOException e) {
      log.warn("读取 reference 失败: location={}", location, e);
      return "";
    }
  }

  private boolean isSafeReferencePath(String referenceFile) {
    return !referenceFile.contains("..")
        && !referenceFile.startsWith("/")
        && !referenceFile.startsWith("\\")
        && referenceFile.matches("[a-zA-Z0-9._/-]+");
  }

  record RefMapping(String ref, boolean shared, String sourceSkillId) {}
}
