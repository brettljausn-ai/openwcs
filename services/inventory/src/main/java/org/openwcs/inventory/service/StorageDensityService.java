package org.openwcs.inventory.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openwcs.inventory.api.StorageDensityRow;
import org.openwcs.inventory.client.MasterDataClient;
import org.openwcs.inventory.client.MasterDataClient.StorageBlockRef;
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

    /** Automated storage-block types whose fill level is the ASRS utilisation (master-data ADR 0003). */
    private static final Set<String> ASRS_TYPES =
            Set.of("SHUTTLE_ASRS", "CRANE_ASRS", "AUTOSTORE", "AMR_GTP");

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
     * Today's live storage utilisation for the dashboard: occupied vs total cells across all
     * storage blocks (overall) and across the automated ASRS blocks only. Reuses the same
     * occupied-cell computation as the snapshot sweep, measured fresh (no snapshot needed).
     * Best-effort: with master-data down both percentages are null.
     */
    public Utilisation todayUtilisation(UUID warehouseId) {
        List<StorageBlockRef> blocks;
        try {
            blocks = masterData.storageBlocks(warehouseId);
        } catch (MasterDataUnavailableException e) {
            log.warn("dashboard utilisation skipped for warehouse {} because master-data is"
                    + " unreachable ({}); returning null percentages", warehouseId, e.getMessage());
            return new Utilisation(null, null);
        }
        int overallOccupied = 0;
        int overallTotal = 0;
        int asrsOccupied = 0;
        int asrsTotal = 0;
        for (StorageBlockRef block : blocks) {
            List<UUID> cells = masterData.blockLocationIds(warehouseId, block.id());
            int occupied = countOccupied(cells);
            overallOccupied += occupied;
            overallTotal += cells.size();
            if (block.storageType() != null && ASRS_TYPES.contains(block.storageType())) {
                asrsOccupied += occupied;
                asrsTotal += cells.size();
            }
        }
        return new Utilisation(pctOrNull(overallOccupied, overallTotal), pctOrNull(asrsOccupied, asrsTotal));
    }

    /** Overall vs ASRS-only fill percentage; either is null when there are no cells to measure. */
    public record Utilisation(Double overallPct, Double asrsPct) {
    }

    private static Double pctOrNull(int occupied, int total) {
        return total == 0 ? null : occupied * 100.0 / total;
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
