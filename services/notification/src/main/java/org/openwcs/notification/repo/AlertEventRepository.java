package org.openwcs.notification.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.notification.domain.AlertEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {

    /** The single active (OPEN or ACKED) alert for a dedupe key, if any (the active-dedupe index keeps it unique). */
    Optional<AlertEvent> findFirstByDedupeKeyAndStateIn(String dedupeKey, List<String> states);

    /** Active alerts (OPEN or ACKED) for a warehouse, newest first. */
    List<AlertEvent> findByWarehouseIdAndStateInOrderByOpenedAtDesc(UUID warehouseId, List<String> states);
}
