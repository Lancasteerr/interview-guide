package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.config.InterviewSkillProperties;
import interview.guide.modules.interview.service.InterviewSkillService.CategoryDTO;
import interview.guide.modules.interview.service.InterviewSkillService.DisplayDTO;
import interview.guide.modules.interview.service.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.service.InterviewSkillService.SkillDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责预设 Skill 目录、Skill 文件解析和自定义 Skill 构建。
 */
@Slf4j
final class InterviewSkillCatalogService {

  private static final int MAX_CATEGORY_LABEL_LENGTH = 50;
  private static final int MAX_CATEGORY_KEY_LENGTH = 50;
  private static final Pattern FRONT_MATTER_PATTERN =
      Pattern.compile("(?s)^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$");
  private static final Pattern SKILL_ID_PATTERN = Pattern.compile(".*/skills/([^/]+)/SKILL\\.md$");
  private static final String SKILL_META_FILE = "skill.meta.yml";

  private final ResourceLoader resourceLoader;
  private final Map<String, InterviewSkillProperties.SkillDefinition> presetRegistry = new TreeMap<>();
  private final InterviewSkillReferenceService referenceService;
  private String cachedReferenceFileList = "（无可用参考文件）";

  InterviewSkillCatalogService(ResourceLoader resourceLoader) {
    this.resourceLoader = resourceLoader;
    this.referenceService = new InterviewSkillReferenceService(resourceLoader, presetRegistry);
  }

  void loadPresetSkills() throws IOException {
    var resolver = new PathMatchingResourcePatternResolver();
    Resource[] resources = resolver.getResources("classpath:skills/*/SKILL.md");
    Yaml yaml = new Yaml();

    for (Resource resource : resources) {
      String skillId = extractSkillId(resource);
      if (skillId == null || "_shared".equals(skillId)) {
        continue;
      }

      InterviewSkillProperties.SkillDefinition definition = parseSkillDefinition(skillId, resource, yaml);
      if (definition.getName() == null || definition.getName().isBlank()) {
        log.warn("跳过无效 Skill（缺少 name）: {}", skillId);
        continue;
      }

      presetRegistry.put(skillId, definition);
      log.info("加载预设 Skill: {} ({})", skillId, definition.getName());
    }

    log.info("共加载 {} 个预设 Skill", presetRegistry.size());
    referenceService.rebuildIndex();
    cachedReferenceFileList = referenceService.buildReferenceFileList();
  }

  List<SkillDTO> getAllSkills() {
    return presetRegistry.entrySet().stream()
        .map(entry -> toSkillDTO(entry.getKey(), entry.getValue()))
        .toList();
  }

  SkillDTO getSkill(String skillId) {
    InterviewSkillProperties.SkillDefinition preset = presetRegistry.get(skillId);
    if (preset != null) {
      return toSkillDTO(skillId, preset);
    }
    throw new BusinessException(ErrorCode.BAD_REQUEST, "未找到面试主题: " + skillId);
  }

  SkillDTO buildCustomSkill(List<CategoryDTO> customCategories, String jdText) {
    List<SkillCategoryDTO> categories = customCategories.stream()
        .filter(category -> category.key() != null && category.label() != null)
        .map(category -> {
          String safeKey = sanitizeCategoryKey(category.key());
          String safeLabel = sanitizeCategoryLabel(category.label());
          InterviewSkillReferenceService.RefMapping refMapping = referenceService.findMapping(safeKey);
          if (refMapping != null) {
            if (!refMapping.ref().equals(category.ref())
                || refMapping.shared() != Boolean.TRUE.equals(category.shared())) {
              log.info("JD 分类 reference 已按本地映射纠正: key={}, modelRef={}, modelShared={}, mappedRef={}, mappedShared={}",
                  safeKey, category.ref(), category.shared(), refMapping.ref(), refMapping.shared());
            }
            return new SkillCategoryDTO(safeKey, safeLabel, category.priority(),
                refMapping.ref(), refMapping.shared());
          }
          return new SkillCategoryDTO(safeKey, safeLabel, category.priority(),
              category.ref(), Boolean.TRUE.equals(category.shared()));
        })
        .toList();

    long matchedCount = categories.stream()
        .filter(category -> category.ref() != null && !category.ref().isBlank())
        .count();
    log.info("构建自定义 Skill: {} 个分类, {} 个匹配到参考文件", categories.size(), matchedCount);
    return new SkillDTO(
        InterviewSkillService.CUSTOM_SKILL_ID,
        "自定义面试（JD 解析）",
        "基于职位描述提取的面试方向",
        categories,
        false,
        jdText,
        null,
        null
    );
  }

  String referenceFileList() {
    return cachedReferenceFileList;
  }

  String buildReferenceSection(SkillDTO skill, Map<String, Integer> allocation) {
    return referenceService.buildReferenceSection(skill, allocation);
  }

  String buildEvaluationReferenceSection(String skillId) {
    return referenceService.buildEvaluationReferenceSection(skillId, this::getSkill);
  }

  String buildEvaluationReferenceSectionSafe(String skillId) {
    return referenceService.buildEvaluationReferenceSectionSafe(skillId, this::getSkill);
  }

  private InterviewSkillProperties.SkillDefinition parseSkillDefinition(
      String skillId, Resource resource, Yaml yaml) {
    try {
      String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
      Matcher matcher = FRONT_MATTER_PATTERN.matcher(markdown);
      if (!matcher.matches()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "Skill 文件格式错误（缺少 front matter）: " + resource.getDescription());
      }

      String frontMatter = matcher.group(1);
      String body = matcher.group(2) != null ? matcher.group(2).trim() : "";
      InterviewSkillProperties.SkillFrontMatterDefinition frontMatterDef =
          yaml.loadAs(frontMatter, InterviewSkillProperties.SkillFrontMatterDefinition.class);

      InterviewSkillProperties.SkillDefinition definition = new InterviewSkillProperties.SkillDefinition();
      if (frontMatterDef != null) {
        definition.setName(frontMatterDef.getName());
        definition.setDescription(frontMatterDef.getDescription());
      }
      if (!body.isBlank()) {
        definition.setPersona(body);
      }

      InterviewSkillProperties.SkillMetaDefinition metaDef = loadSkillMetaDefinition(skillId, yaml);
      if (metaDef != null) {
        definition.setDisplayName(metaDef.getDisplayName());
        definition.setDisplay(metaDef.getDisplay());
        definition.setCategories(metaDef.getCategories());
      }
      if (definition.getCategories() == null) {
        definition.setCategories(List.of());
      }
      return definition;
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR,
          "读取 Skill 文件失败: " + resource.getDescription());
    }
  }

  private InterviewSkillProperties.SkillMetaDefinition loadSkillMetaDefinition(String skillId, Yaml yaml) {
    String location = "classpath:skills/" + skillId + "/" + SKILL_META_FILE;
    Resource resource = resourceLoader.getResource(location);
    if (!resource.exists()) {
      log.warn("skill meta 文件不存在，使用默认配置: skillId={}, location={}", skillId, location);
      return null;
    }

    try {
      String content = resource.getContentAsString(StandardCharsets.UTF_8);
      InterviewSkillProperties.SkillMetaDefinition meta =
          yaml.loadAs(content, InterviewSkillProperties.SkillMetaDefinition.class);
      return meta != null ? meta : new InterviewSkillProperties.SkillMetaDefinition();
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取 skill meta 文件失败: " + location);
    }
  }

  private String extractSkillId(Resource resource) {
    try {
      String normalized = resource.getURL().toString().replace('\\', '/');
      Matcher matcher = SKILL_ID_PATTERN.matcher(normalized);
      return matcher.matches() ? matcher.group(1) : null;
    } catch (IOException e) {
      return null;
    }
  }

  private String sanitizeCategoryKey(String key) {
    if (key == null || key.isBlank()) {
      return "UNKNOWN";
    }
    String trimmed = key.trim();
    if (trimmed.length() > MAX_CATEGORY_KEY_LENGTH) {
      trimmed = trimmed.substring(0, MAX_CATEGORY_KEY_LENGTH);
    }
    String upper = trimmed.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
    if (upper.isEmpty()) {
      return "UNKNOWN";
    }
    return Character.isLetter(upper.charAt(0)) ? upper : "CAT_" + upper;
  }

  private String sanitizeCategoryLabel(String label) {
    if (label == null || label.isBlank()) {
      return "未命名";
    }
    String trimmed = label.trim().replaceAll("[\\r\\n]+", " ");
    return trimmed.length() > MAX_CATEGORY_LABEL_LENGTH
        ? trimmed.substring(0, MAX_CATEGORY_LABEL_LENGTH)
        : trimmed;
  }

  private SkillDTO toSkillDTO(String id, InterviewSkillProperties.SkillDefinition definition) {
    String skillDisplayName = definition.getDisplayName() != null
        && !definition.getDisplayName().isBlank()
        ? definition.getDisplayName()
        : definition.getName();
    InterviewSkillProperties.DisplayDef display = definition.getDisplay();
    DisplayDTO displayDTO = display != null
        ? new DisplayDTO(display.getIcon(), display.getGradient(), display.getIconBg(), display.getIconColor())
        : null;
    List<SkillCategoryDTO> categories = definition.getCategories() == null
        ? List.of()
        : definition.getCategories().stream()
            .map(category -> new SkillCategoryDTO(
                category.getKey(), category.getLabel(), category.getPriority(), category.getRef(),
                Boolean.TRUE.equals(category.getShared())))
            .toList();
    return new SkillDTO(
        id,
        skillDisplayName,
        definition.getDescription(),
        categories,
        true,
        null,
        definition.getPersona(),
        displayDTO
    );
  }
}
