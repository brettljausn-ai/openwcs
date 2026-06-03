package org.openwcs.gtp.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.StationNode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StationNodeRepository extends JpaRepository<StationNode, UUID> {
    List<StationNode> findByStationIdOrderByPositionAsc(UUID stationId);

    List<StationNode> findByStationIdAndRole(UUID stationId, String role);
}
