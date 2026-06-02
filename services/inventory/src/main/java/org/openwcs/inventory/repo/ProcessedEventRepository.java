package org.openwcs.inventory.repo;

import java.util.UUID;
import org.openwcs.inventory.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
