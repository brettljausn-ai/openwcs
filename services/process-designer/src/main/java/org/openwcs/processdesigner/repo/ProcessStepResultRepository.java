package org.openwcs.processdesigner.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.processdesigner.domain.ProcessStepResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessStepResultRepository
        extends JpaRepository<ProcessStepResult, ProcessStepResult.Key> {

    Optional<ProcessStepResult> findByInstanceIdAndStepId(UUID instanceId, String stepId);
}
