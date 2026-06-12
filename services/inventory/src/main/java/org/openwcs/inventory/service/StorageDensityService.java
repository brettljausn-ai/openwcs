package org.openwcs.inventory.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.openwcs.inventory.api.StorageDensityRow;
import org.openwcs.inventory.client.MasterDataClient;
import org.openwcs.inventory.client.MasterDataUnavailableException;
import org.openwcs.inventory.domain.StorageDensitySnapshot;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.openwcs.inventory.repo.StorageDensitySnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Storage-density history (spec: "Storage Density in figures and %, show 90 day history").
 * A snapshot is one row per storage block per day: total cells = the block's locations
 * (master-data), occupied cells = how many of them hold any stock row or handling unit
 * (inventory-local). Snapshots are written by the daily {@link StorageDensitySweeper} and
 * on demand by {@link #history} when today has no snapshot yet, so the report answers
 * immediately after deploy. Upserts make reruns idempotent (unique warehouse+block+day).
 */
@Service
public class StorageDensityService {

    private static final Logger log = LoggerFactory.getLogger(StorageDensityService.class);

    /** Chunk size for the occupied-locations IN queries (keeps bind-parameter counts sane). */
    private static final int LOCATION_CHUNK = 1000;

    private final StorageDensitySnapshotRepository snapshots;
    private final StockRepository stock;
    private final HandlingUnitRepository handlingUnits;
    private final MasterDataClient masterData;

    public StorageDensityService(
            StorageDensitySnapshotRepository snapshots,
            StockRepository stock,
            HandlingUnitRepository handlingUnits,
            MasterDataClient masterData) {
        this.snapshots = snapshots;
        this.stock = stock;
        this.handlingUnits = handlingUnits;
        this.masterData = masterData;
    }

    /**
     * The density history window, oldest day first. When today has no snapshot yet for this
     * warehouse, one is taken on demand first (best effort: with master-data down the stored
     * history is served unchanged).
     */
    public List<StorageDensityRow> history(UUID warehouseId, int days) {
        LocalDate today = today();
        if (!snapshots.existsByWarehouseIdAndDay(warehouseId, today)) {
            try {
                snapshotWarehouse(warehouseId, today);
            } catch (MasterDataUnavailableException e) {
                log.warn("on-demand storage-density snapshot skipped for warehouse {} because"
                        + " master-data is unreachable ({}); serving the stored history only",
                        warehouseId, e.getMessage());
            }
        }
        LocalDate since = today.minusDays(Math.max(days, 1) - 1L);
        return snapshots.findByWarehouseIdAndDayGreaterThanEqualOrderByDayAscBlockIdAsc(warehouseId, since)
                .stream()
                .map(s -> new StorageDensityRow(
                        s.getBlockId(), s.getDay(), s.getOccupiedCells(), s.getTotalCells(),
                        pct(s.getOccupiedCells(), s.getTotalCells())))
                .toList();
    }

    /**
     * Snapshot every storage block of the warehouse for the given day (idempotent upsert).
     *
     * @return how many blocks were snapshotted
     * @throws MasterDataUnavailableException when the block/location topology cannot be listed
     */
    public int snapshotWarehouse(UUID warehouseId, LocalDate day) {
        List<UUID> blocks = masterData.storageBlockIds(warehouseId);
        for (UUID blockId : blocks) {
            List<UUID> cells = masterData.blockLocationIds(warehouseId, blockId);
            int occupied = countOccupied(cells);
            StorageDensitySnapshot snapshot = snapshots
                    .findByWarehouseIdAndBlockIdAndDay(warehouseId, blockId, day)
                    .orElseGet(() -> {
                        StorageDensitySnapshot s = new StorageDensitySnapshot();
                        s.setWarehouseId(warehouseId);
                        s.setBlockId(blockId);
                        s.setDay(day);
                        return s;
                    });
            snapshot.setOccupiedCells(occupied);
            snapshot.setTotalCells(cells.size());
            snapshots.save(snapshot);
            log.info("storage-density snapshot: block {} of warehouse {} on {} holds stock/HUs in"
                    + " {} of {} cells ({}%)", blockId, warehouseId, day, occupied, cells.size(),
                    String.format(java.util.Locale.ROOT, "%.1f", pct(occupied, cells.size())));
        }
        return blocks.size();
    }

    /** Distinct cells (of the given ones) holding any stock row or handling unit. */
    private int countOccupied(List<UUID> cells) {
        Set<UUID> occupied = new HashSet<>();
        for (int from = 0; from < cells.size(); from += LOCATION_CHUNK) {
            List<UUID> chunk = cells.subList(from, Math.min(from + LOCATION_CHUNK, cells.size()));
            occupied.addAll(stock.findDistinctLocationIdByLocationIdIn(chunk));
            occupied.addAll(handlingUnits.findDistinctLocationIdByLocationIdIn(chunk));
        }
        return occupied.size();
    }

    /** Report days are UTC calendar days (matches the stored UTC timestamps). */
    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private static double pct(int occupied, int total) {
        return total == 0 ? 0.0 : occupied * 100.0 / total;
    }
}
