package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 面试方向 Skill 门面：兼容原有接口，委托目录、JD 解析、分配和参考内容服务。
 */
@Service
public class InterviewSkillService {

  public static final String CUSTOM_SKILL_ID = "custom";

  private final InterviewSkillCatalogService catalogService;
  private final InterviewJdParserService jdParserService;
  private final InterviewSkillAllocationService allocationService;

  public InterviewSkillService(
      LlmProviderRegistry llmProviderRegistry,
      StructuredOutputInvoker structuredOutputInvoker,
      ResourceLoader resourceLoader,
      PromptSanitizer promptSanitizer) throws IOException {
    this.catalogService = new InterviewSkillCatalogService(resourceLoader);
    this.jdParserService = new InterviewJdParserService(
        llmProviderRegistry, structuredOutputInvoker, resourceLoader, promptSanitizer);
    this.allocationService = new InterviewSkillAllocationService();
  }

  @PostConstruct
  void loadPresetSkills() throws IOException {
    catalogService.loadPresetSkills();
  }

  public List<SkillDTO> getAllSkills() {
    return catalogService.getAllSkills();
  }

  public SkillDTO getSkill(String skillId) {
    return catalogService.getSkill(skillId);
  }

  public SkillDTO buildCustomSkill(List<CategoryDTO> customCategories, String jdText) {
    return catalogService.buildCustomSkill(customCategories, jdText);
  }

  public List<CategoryDTO> parseJd(String jdText) {
    return jdParserService.parse(jdText, catalogService.referenceFileList());
  }

  public Map<String, Integer> calculateAllocation(String skillId, int totalQuestions) {
    return calculateAllocation(getSkill(skillId).categories(), totalQuestions);
  }

  public Map<String, Integer> calculateAllocation(List<SkillCategoryDTO> categories, int totalQuestions) {
    return allocationService.calculateAllocation(categories, totalQuestions);
  }

  public String buildAllocationDescription(
      Map<String, Integer> allocation, List<SkillCategoryDTO> categories) {
    return allocationService.buildDescription(allocation, categories);
  }

  public String buildReferenceSection(SkillDTO skill, Map<String, Integer> allocation) {
    return catalogService.buildReferenceSection(skill, allocation);
  }

  public String buildEvaluationReferenceSection(String skillId) {
    return catalogService.buildEvaluationReferenceSection(skillId);
  }

  public String buildEvaluationReferenceSectionSafe(String skillId) {
    return catalogService.buildEvaluationReferenceSectionSafe(skillId);
  }

  public record SkillDTO(
      String id,
      String name,
      String description,
      List<SkillCategoryDTO> categories,
      boolean isPreset,
      String sourceJd,
      String persona,
      DisplayDTO display) {}

  public record DisplayDTO(String icon, String gradient, String iconBg, String iconColor) {}

  public record SkillCategoryDTO(
      String key, String label, String priority, String ref, boolean shared) {}

  public record CategoryDTO(String key, String label, String priority, String ref, Boolean shared) {}
}
