package org.openwcs.processdesigner.repo;

import java.util.UUID;
import org.openwcs.processdesigner.domain.ProcessInstance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessInstanceRepository extends JpaRepository<ProcessInstance, UUID> {
}
