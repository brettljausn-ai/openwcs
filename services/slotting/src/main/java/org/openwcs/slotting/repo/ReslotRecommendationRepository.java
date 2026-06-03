package org.openwcs.slotting.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.domain.ReslotRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReslotRecommendationRepository extends JpaRepository<ReslotRecommendation, UUID> {
    List<ReslotRecommendation> findByWarehouseIdAndStatus(UUID warehouseId, String status);
}
