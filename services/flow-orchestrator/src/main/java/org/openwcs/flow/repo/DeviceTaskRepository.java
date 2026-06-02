package org.openwcs.flow.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.DeviceTask;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTaskRepository extends JpaRepository<DeviceTask, UUID> {
    List<DeviceTask> findByCorrelationIdOrderByCreatedAtAsc(UUID correlationId);
}
