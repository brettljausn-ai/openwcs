package org.openwcs.masterdata.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.LabelTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LabelTemplateRepository extends JpaRepository<LabelTemplate, UUID> {
    Optional<LabelTemplate> findByCode(String code);
}
