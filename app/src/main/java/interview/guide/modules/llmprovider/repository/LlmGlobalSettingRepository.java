package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.entity.LlmGlobalSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmGlobalSettingRepository extends JpaRepository<LlmGlobalSettingEntity, Long> {
}
