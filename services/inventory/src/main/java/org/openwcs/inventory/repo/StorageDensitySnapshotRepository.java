package org.openwcs.inventory.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.inventory.domain.StorageDensitySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageDensitySnapshotRepository extends JpaRepository<StorageDensitySnapshot, UUID> {

    /** The snapshot of one block on one day (unique), for the idempotent daily upsert. */
    Optional<StorageDensitySnapshot> findByWarehouseIdAndBlockIdAndDay(
            UUID warehouseId, UUID blockId, LocalDate day);

    /** Whether any block of the warehouse was snapshotted on the given day (on-demand trigger). */
    boolean existsByWarehouseIdAndDay(UUID warehouseId, LocalDate day);

    /** History window for the report, oldest day first. */
    List<StorageDensitySnapshot> findByWarehouseIdAndDayGreaterThanEqualOrderByDayAscBlockIdAsc(
            UUID warehouseId, LocalDate since);
}
