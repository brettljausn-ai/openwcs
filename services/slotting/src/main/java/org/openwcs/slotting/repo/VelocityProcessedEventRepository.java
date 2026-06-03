package org.openwcs.slotting.repo;

import java.util.UUID;
import org.openwcs.slotting.domain.VelocityProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VelocityProcessedEventRepository extends JpaRepository<VelocityProcessedEvent, UUID> {
}
