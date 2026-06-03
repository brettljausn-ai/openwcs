package org.openwcs.gtp.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.gtp.domain.WorkplaceSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkplaceSessionRepository extends JpaRepository<WorkplaceSession, UUID> {

    /** The single active session for a workplace, if any (enforced unique by a partial index). */
    Optional<WorkplaceSession> findByStationIdAndStatus(UUID stationId, String status);

    /** All active sessions across a set of workplaces — for listing which workplaces are in use. */
    List<WorkplaceSession> findByStationIdInAndStatus(List<UUID> stationIds, String status);
}
