package org.openwcs.gtp.repo;

import java.util.UUID;
import org.openwcs.gtp.domain.MaintenanceOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceOrderRepository extends JpaRepository<MaintenanceOrder, UUID> {
}
