package interview.guide.modules.interview.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试技能分类的题量分配器。
 */
@Slf4j
final class InterviewSkillAllocationService {

  Map<String, Integer> calculateAllocation(
      List<InterviewSkillService.SkillCategoryDTO> categories,
      int totalQuestions) {
    List<InterviewSkillService.SkillCategoryDTO> alwaysOneCats = new ArrayList<>();
    List<InterviewSkillService.SkillCategoryDTO> coreCats = new ArrayList<>();
    List<InterviewSkillService.SkillCategoryDTO> normalCats = new ArrayList<>();

    for (InterviewSkillService.SkillCategoryDTO category : categories) {
      switch (category.priority()) {
        case "ALWAYS_ONE" -> alwaysOneCats.add(category);
        case "CORE" -> coreCats.add(category);
        default -> normalCats.add(category);
      }
    }

    Map<String, Integer> allocation = new LinkedHashMap<>();
    int remaining = totalQuestions;

    for (InterviewSkillService.SkillCategoryDTO category : alwaysOneCats) {
      if (remaining > 0) {
        allocation.put(category.key(), 1);
        remaining--;
      }
    }

    for (InterviewSkillService.SkillCategoryDTO category : coreCats) {
      if (remaining > 0) {
        allocation.put(category.key(), 1);
        remaining--;
      }
    }
    for (InterviewSkillService.SkillCategoryDTO category : normalCats) {
      if (remaining > 0) {
        allocation.put(category.key(), 1);
        remaining--;
      }
    }

    while (remaining > 0) {
      for (InterviewSkillService.SkillCategoryDTO category : coreCats) {
        if (remaining <= 0) break;
        allocation.merge(category.key(), 1, Integer::sum);
        remaining--;
      }
      for (InterviewSkillService.SkillCategoryDTO category : normalCats) {
        if (remaining <= 0) break;
        allocation.merge(category.key(), 1, Integer::sum);
        remaining--;
      }
      if (coreCats.isEmpty() && normalCats.isEmpty()) break;
    }

    for (InterviewSkillService.SkillCategoryDTO category : coreCats) {
      allocation.putIfAbsent(category.key(), 0);
    }
    for (InterviewSkillService.SkillCategoryDTO category : normalCats) {
      allocation.putIfAbsent(category.key(), 0);
    }

    log.debug("题目分配: total={}, allocation={}", totalQuestions, allocation);
    return allocation;
  }

  String buildDescription(
      Map<String, Integer> allocation,
      List<InterviewSkillService.SkillCategoryDTO> categories) {
    StringBuilder sb = new StringBuilder();
    for (InterviewSkillService.SkillCategoryDTO category : categories) {
      int count = allocation.getOrDefault(category.key(), 0);
      if (count > 0) {
        sb.append("| ").append(category.label()).append(" | ").append(count)
            .append(" 题 | ").append(category.priority()).append(" |\n");
      }
    }
    return sb.toString();
  }
}
