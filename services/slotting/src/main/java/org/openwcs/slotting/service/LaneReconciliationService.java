package org.openwcs.slotting.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.openwcs.slotting.api.LaneReconciliationReport;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.client.MasterDataClient.Block;
import org.openwcs.slotting.client.MasterDataClient.StorageLocation;
import org.openwcs.slotting.domain.PutawayAssignment;
import org.openwcs.slotting.repo.PutawayAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lane-depth ledger reconciliation (ADR 0003 fast-follow).
 *
 * <p>For each multi-deep lane (a channel of cells at increasing depth, {@code laneDepth > 1}) the
 * slotting engine reasons with an <em>assumed</em> occupied depth derived from its own ledger: the
 * active put-away assignments whose chosen location sits in the lane. Over time this picture drifts
 * from the physical truth (HUs actually in the cells), e.g. when a store/move/pick completed without
 * the assignment being closed (a ghost) or a cell was filled outside the engine.
 *
 * <p>This service pulls the lane cells from master-data, asks inventory which of those cells are
 * actually occupied (reusing {@link InventoryClient#occupiedLocations}, the
 * {@code POST /api/inventory/locations/occupied} projection), and compares the assumed vs actual
 * occupied depth per lane. Where the ledger marks a cell occupied that inventory reports empty
 * (a ghost assignment) the assignment is corrected to truth by being closed
 * ({@code status = RECONCILED}); the rest of the report is advisory. Best-effort: if inventory is
 * unavailable the lane is skipped, so the sweep degrades to an empty report and never crashes.
 */
@Service
public class LaneReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(LaneReconciliationService.class);

    /** Statuses that count as "the engine believes this cell holds an HU". */
    private static final List<String> ACTIVE = List.of("PLANNED", "DISPATCHED");
    private static final String RECONCILED = "RECONCILED";

    private final MasterDataClient masterData;
    private final InventoryClient inventory;
    private final PutawayAssignmentRepository assignments;

    public LaneReconciliationService(MasterDataClient masterData, InventoryClient inventory,
                                     PutawayAssignmentRepository assignments) {
        this.masterData = masterData;
        this.inventory = inventory;
        this.assignments = assignments;
    }

    /** Reconcile every storage block of a warehouse. */
    @Transactional
    public LaneReconciliationReport reconcile(UUID warehouseId) {
        int lanesChecked = 0;
        int corrected = 0;
        List<LaneReconciliationReport.Discrepancy> discrepancies = new ArrayList<>();
        for (Block block : masterData.blocks(warehouseId)) {
            LaneReconciliationReport part = reconcileBlock(warehouseId, block.id());
            lanesChecked += part.lanesChecked();
            corrected += part.corrected();
            discrepancies.addAll(part.discrepancies());
        }
        return new LaneReconciliationReport(lanesChecked, corrected, discrepancies);
    }

    /** Reconcile the multi-deep lanes of a single block. */
    @Transactional
    public LaneReconciliationReport reconcileBlock(UUID warehouseId, UUID blockId) {
        List<StorageLocation> locations = masterData.storageLocations(warehouseId, blockId).stream()
                .filter(l -> "STORAGE".equals(l.purpose()))
                .filter(l -> BlockOccupancy.laneDepth(l) > 1) // only multi-deep lanes drift on depth
                .toList();
        if (locations.isEmpty()) {
            return new LaneReconciliationReport(0, 0, List.of());
        }

        // The engine's occupancy ledger for the block, keyed by cell.
        Map<UUID, List<PutawayAssignment>> ledgerByCell = new LinkedHashMap<>();
        for (PutawayAssignment a : assignments.findByWarehouseIdAndBlockIdAndStatusIn(warehouseId, blockId, ACTIVE)) {
            if (a.getChosenLocationId() != null) {
                ledgerByCell.computeIfAbsent(a.getChosenLocationId(), k -> new ArrayList<>()).add(a);
            }
        }

        // Group the cells into lanes (channels sharing aisle + level + column, differing by depth).
        Map<String, List<StorageLocation>> lanes = new LinkedHashMap<>();
        for (StorageLocation l : locations) {
            lanes.computeIfAbsent(laneKey(l), k -> new ArrayList<>()).add(l);
        }

        // One occupancy lookup for the whole block (best-effort; empty on inventory failure).
        List<UUID> cellIds = locations.stream().map(StorageLocation::id).toList();
        Set<UUID> occupied;
        try {
            occupied = inventory.occupiedLocations(warehouseId, cellIds);
        } catch (RuntimeException e) {
            log.warn("lane reconciliation for block {} skipped: inventory occupancy unavailable ({})",
                    blockId, e.toString());
            return new LaneReconciliationReport(0, 0, List.of());
        }

        int lanesChecked = 0;
        int corrected = 0;
        List<LaneReconciliationReport.Discrepancy> discrepancies = new ArrayList<>();
        for (Map.Entry<String, List<StorageLocation>> lane : lanes.entrySet()) {
            lanesChecked++;
            List<StorageLocation> cells = lane.getValue();

            int expectedDepth = 0;
            int actualDepth = 0;
            for (StorageLocation cell : cells) {
                boolean ledgerOccupied = ledgerByCell.containsKey(cell.id());
                boolean reallyOccupied = occupied.contains(cell.id());
                if (ledgerOccupied) {
                    expectedDepth++;
                }
                if (reallyOccupied) {
                    actualDepth++;
                }
                // Ghost: the engine holds an assignment for a cell inventory reports empty. Correct
                // to truth by closing the assignment so the engine stops counting it occupied.
                if (ledgerOccupied && !reallyOccupied) {
                    for (PutawayAssignment ghost : ledgerByCell.get(cell.id())) {
                        ghost.setStatus(RECONCILED);
                        assignments.save(ghost);
                        corrected++;
                        log.info("lane reconciliation corrected ghost assignment {}: cell {} in lane {}"
                                        + " (block {}) was assumed occupied but inventory reports it empty",
                                ghost.getId(), cell.id(), lane.getKey(), blockId);
                    }
                }
            }

            if (expectedDepth != actualDepth) {
                discrepancies.add(new LaneReconciliationReport.Discrepancy(
                        blockId, lane.getKey(), faceCell(cells), expectedDepth, actualDepth));
                log.info("lane-depth drift in block {} lane {}: slotting assumed {} occupied, inventory has {}",
                        blockId, lane.getKey(), expectedDepth, actualDepth);
            }
        }
        return new LaneReconciliationReport(lanesChecked, corrected, discrepancies);
    }

    /**
     * Stable lane identity within a block: a channel is the set of cells sharing aisle + vertical
     * level + column, differing only in depth ({@code posZ}). Where the cell coordinate is not
     * maintained the aisle alone keys the lane (coarser, but still groups one multi-deep block).
     */
    private static String laneKey(StorageLocation l) {
        String aisle = l.aisle() == null ? "" : l.aisle();
        if (l.posX() == null && l.posY() == null) {
            return "aisle=" + aisle;
        }
        return "aisle=" + aisle + ";level=" + l.posY() + ";col=" + l.posX();
    }

    /** The aisle-face cell of a lane (lowest depth) as the representative location. */
    private static UUID faceCell(List<StorageLocation> cells) {
        return cells.stream()
                .min(Comparator.comparingInt(c -> c.posZ() == null ? Integer.MAX_VALUE : c.posZ()))
                .map(StorageLocation::id)
                .orElse(cells.get(0).id());
    }
}
