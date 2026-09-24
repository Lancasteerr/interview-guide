package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.interview.config.InterviewSkillProperties;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面试方向 Skill 管理：分类分配、References 注入、自定义 Skill 构建。
 *
 * 与 SkillsTool 互补：SkillsTool 负责 LLM 按需加载 persona（SKILL.md body），
 * 本类负责后端解析分类配置（skill.meta.yml）并批量注入 references 到 Prompt。
 */
@Slf4j
@Service
public class InterviewSkillService {

    public static final String CUSTOM_SKILL_ID = "custom";

    private static final int MIN_JD_LENGTH = 50;
    private static final int MAX_CATEGORY_LABEL_LENGTH = 50;
    private static final int MAX_CATEGORY_KEY_LENGTH = 50;

    private static final Pattern FRONT_MATTER_PATTERN = Pattern.compile("(?s)^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$");
    private static final Pattern SKILL_ID_PATTERN = Pattern.compile(".*/skills/([^/]+)/SKILL\\.md$");
    private static final String SKILL_META_FILE = "skill.meta.yml";
    private static final String JD_PARSE_SYSTEM_PROMPT_PATH = "classpath:prompts/interview/jd-parse-system.st";

    private final LlmProviderRegistry llmProviderRegistry;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final BeanOutputConverter<CategoryListDTO> jdOutputConverter;
    private final PromptTemplate jdSystemPromptTemplate;
    private final ResourceLoader resourceLoader;
    private final PromptSanitizer promptSanitizer;
    private final InterviewSkillAllocationService allocationService = new InterviewSkillAllocationService();

    /** 预设 Skill 注册表，启动时从 classpath:skills/{skillId}/SKILL.md 加载 */
    private final Map<String, InterviewSkillProperties.SkillDefinition> presetRegistry = new TreeMap<>();

    /** JD 解析用的参考文件清单 Markdown 表格，启动时生成一次 */
    private String cachedReferenceFileList;
    private final InterviewSkillReferenceService referenceService;

    public InterviewSkillService(LlmProviderRegistry llmProviderRegistry,
                                 StructuredOutputInvoker structuredOutputInvoker,
                                 ResourceLoader resourceLoader,
                                 PromptSanitizer promptSanitizer) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.resourceLoader = resourceLoader;
        this.promptSanitizer = promptSanitizer;
        this.referenceService = new InterviewSkillReferenceService(resourceLoader, presetRegistry);
        this.jdOutputConverter = new BeanOutputConverter<>(CategoryListDTO.class) {};
        this.jdSystemPromptTemplate = new PromptTemplate(loadClasspathPrompt(JD_PARSE_SYSTEM_PROMPT_PATH));
    }

    @PostConstruct
    void loadPresetSkills() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:skills/*/SKILL.md");
        Yaml yaml = new Yaml();

        for (Resource resource : resources) {
            String skillId = extractSkillId(resource);
            if (skillId == null || "_shared".equals(skillId)) {
                continue;
            }

            InterviewSkillProperties.SkillDefinition def = parseSkillDefinition(skillId, resource, yaml);
            if (def.getName() == null || def.getName().isBlank()) {
                log.warn("跳过无效 Skill（缺少 name）: {}", skillId);
                continue;
            }

            presetRegistry.put(skillId, def);
            log.info("加载预设 Skill: {} ({})", skillId, def.getName());
        }

        log.info("共加载 {} 个预设 Skill", presetRegistry.size());

        referenceService.rebuildIndex();
        cachedReferenceFileList = referenceService.buildReferenceFileList();
    }

    public List<SkillDTO> getAllSkills() {
        return presetRegistry.entrySet().stream()
            .map(e -> toSkillDTO(e.getKey(), e.getValue()))
            .toList();
    }

    public SkillDTO getSkill(String skillId) {
        InterviewSkillProperties.SkillDefinition preset = presetRegistry.get(skillId);
        if (preset != null) {
            return toSkillDTO(skillId, preset);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "未找到面试主题: " + skillId);
    }

    /**
     * 从 JD 解析结果构建自定义 SkillDTO。
     * 遍历 customCategories，尝试在 categoryRefIndex 中匹配参考文件。
     */
    public SkillDTO buildCustomSkill(List<CategoryDTO> customCategories, String jdText) {
        List<SkillCategoryDTO> categories = customCategories.stream()
            .filter(cat -> cat.key() != null && cat.label() != null)
            .map(cat -> {
                String safeKey = sanitizeCategoryKey(cat.key());
                String safeLabel = sanitizeCategoryLabel(cat.label());
                InterviewSkillReferenceService.RefMapping refMapping = referenceService.findMapping(safeKey);
                if (refMapping != null) {
                    if (!refMapping.ref().equals(cat.ref())
                        || refMapping.shared() != Boolean.TRUE.equals(cat.shared())) {
                        log.info("JD 分类 reference 已按本地映射纠正: key={}, modelRef={}, modelShared={}, mappedRef={}, mappedShared={}",
                            safeKey, cat.ref(), cat.shared(), refMapping.ref(), refMapping.shared());
                    }
                    return new SkillCategoryDTO(safeKey, safeLabel, cat.priority(),
                        refMapping.ref(), refMapping.shared());
                }
                return new SkillCategoryDTO(safeKey, safeLabel, cat.priority(),
                    cat.ref(), Boolean.TRUE.equals(cat.shared()));
            })
            .toList();

        long matchedCount = categories.stream().filter(c -> c.ref() != null && !c.ref().isBlank()).count();
        log.info("构建自定义 Skill: {} 个分类, {} 个匹配到参考文件", categories.size(), matchedCount);

        return new SkillDTO(CUSTOM_SKILL_ID, "自定义面试（JD 解析）",
            "基于职位描述提取的面试方向", categories,
            false, jdText, null, null);
    }

    public List<CategoryDTO> parseJd(String jdText) {
        if (jdText == null || jdText.length() < MIN_JD_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "JD 内容太少（至少 " + MIN_JD_LENGTH + " 字），请补充后重试");
        }

        log.info("开始解析 JD，长度: {}", jdText.length());

        ChatClient chatClient = llmProviderRegistry.getDefaultChatClient();
        String systemPrompt = jdSystemPromptTemplate.render(Map.of(
            "referenceFileList", cachedReferenceFileList
        )) + "\n\n" + jdOutputConverter.getFormat();
        String userPrompt = PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
            "职位描述：\n" +
            promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(jdText));

        try {
            CategoryListDTO result = structuredOutputInvoker.invoke(
                chatClient, systemPrompt, userPrompt, jdOutputConverter,
                ErrorCode.AI_SERVICE_ERROR, "JD 解析失败：", "JD 解析", log
            );

            if (result == null || result.categories() == null || result.categories().isEmpty()) {
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析结果为空，请重试");
            }

            long refMatched = result.categories().stream().filter(c -> c.ref() != null && !c.ref().isBlank()).count();
            log.info("JD 解析完成: {} 个方向, {} 个匹配到参考文件", result.categories().size(), refMatched);
            return result.categories();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("JD 解析失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析失败，请重试或选择预设主题");
        }
    }

    public Map<String, Integer> calculateAllocation(String skillId, int totalQuestions) {
        return calculateAllocation(getSkill(skillId).categories(), totalQuestions);
    }

    public Map<String, Integer> calculateAllocation(List<SkillCategoryDTO> categories, int totalQuestions) {
        return allocationService.calculateAllocation(categories, totalQuestions);
    }

    public String buildAllocationDescription(Map<String, Integer> allocation, List<SkillCategoryDTO> categories) {
        return allocationService.buildDescription(allocation, categories);
    }

    public String buildReferenceSection(SkillDTO skill, Map<String, Integer> allocation) {
        return referenceService.buildReferenceSection(skill, allocation);
    }

    /**
     * 评估阶段参考基线：不限制题量分配，覆盖该 skill 下所有配置了 reference 的分类。
     */
    public String buildEvaluationReferenceSection(String skillId) {
        return referenceService.buildEvaluationReferenceSection(skillId, this::getSkill);
    }

    /**
     * 安全版本的评估参考基线：skillId 为空或加载失败时返回空字符串，不抛异常。
     */
    public String buildEvaluationReferenceSectionSafe(String skillId) {
        return referenceService.buildEvaluationReferenceSectionSafe(skillId, this::getSkill);
    }

    private String loadClasspathPrompt(String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    private InterviewSkillProperties.SkillDefinition parseSkillDefinition(String skillId, Resource resource, Yaml yaml) {
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
            InterviewSkillProperties.SkillMetaDefinition meta = yaml.loadAs(content, InterviewSkillProperties.SkillMetaDefinition.class);
            return meta != null ? meta : new InterviewSkillProperties.SkillMetaDefinition();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取 skill meta 文件失败: " + location);
        }
    }

    private String extractSkillId(Resource resource) {
        try {
            String normalized = resource.getURL().toString().replace('\\', '/');
            Matcher matcher = SKILL_ID_PATTERN.matcher(normalized);
            if (matcher.matches()) {
                return matcher.group(1);
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 清洗 category key：截断长度，非法字符替换为下划线，转大写。
     * 首字符必须为字母，否则添加 "CAT_" 前缀。
     */
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
        // 确保首字符为字母（匹配 category key 命名规范）
        if (!Character.isLetter(upper.charAt(0))) {
            upper = "CAT_" + upper;
        }
        return upper;
    }

    /**
     * 清洗 category label：截断长度，移除换行。
     */
    private String sanitizeCategoryLabel(String label) {
        if (label == null || label.isBlank()) {
            return "未命名";
        }
        String trimmed = label.trim().replaceAll("[\\r\\n]+", " ");
        if (trimmed.length() > MAX_CATEGORY_LABEL_LENGTH) {
            trimmed = trimmed.substring(0, MAX_CATEGORY_LABEL_LENGTH);
        }
        return trimmed;
    }

    private SkillDTO toSkillDTO(String id, InterviewSkillProperties.SkillDefinition def) {
        String skillDisplayName = (def.getDisplayName() != null && !def.getDisplayName().isBlank())
            ? def.getDisplayName()
            : def.getName();

        InterviewSkillProperties.DisplayDef disp = def.getDisplay();
        DisplayDTO displayDTO = disp != null
            ? new DisplayDTO(disp.getIcon(), disp.getGradient(), disp.getIconBg(), disp.getIconColor())
            : null;

        List<SkillCategoryDTO> categories = def.getCategories() == null
            ? List.of()
            : def.getCategories().stream()
                .map(c -> new SkillCategoryDTO(
                    c.getKey(),
                    c.getLabel(),
                    c.getPriority(),
                    c.getRef(),
                    Boolean.TRUE.equals(c.getShared())
                ))
                .toList();

        return new SkillDTO(
            id,
            skillDisplayName,
            def.getDescription(),
            categories,
            true,
            null,
            def.getPersona(),
            displayDTO
        );
    }

    public record SkillDTO(String id, String name, String description,
                           List<SkillCategoryDTO> categories,
                           boolean isPreset, String sourceJd, String persona, DisplayDTO display) {}

    public record DisplayDTO(String icon, String gradient, String iconBg, String iconColor) {}

    /**
     * 预设 Skill 分类（可携带 references 绑定信息）
     */
    public record SkillCategoryDTO(String key, String label, String priority, String ref, boolean shared) {}

    /**
     * JD 解析返回分类（可携带 LLM 匹配的 ref/shared 信息，后端会按本地 categoryRefIndex 纠正）
     */
    public record CategoryDTO(String key, String label, String priority,
                               String ref, Boolean shared) {}

    private record CategoryListDTO(List<CategoryDTO> categories) {}
}
