package org.openwcs.counting.repo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.openwcs.counting.domain.CountSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CountScheduleRepository extends JpaRepository<CountSchedule, UUID> {

    List<CountSchedule> findByWarehouseId(UUID warehouseId);

    /** Active schedules whose next-due time has passed (the generator's due set). */
    List<CountSchedule> findByStatusAndNextDueAtLessThanEqual(String status, Instant cutoff);

    List<CountSchedule> findByWarehouseIdAndStatusAndNextDueAtLessThanEqual(
            UUID warehouseId, String status, Instant cutoff);
}
