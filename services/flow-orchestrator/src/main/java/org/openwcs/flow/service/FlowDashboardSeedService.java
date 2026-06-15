package org.openwcs.flow.service;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.openwcs.flow.api.DemoDashboardSeedResult;
import org.openwcs.flow.client.MasterDataClient;
import org.openwcs.flow.domain.DeviceTask;
import org.openwcs.flow.domain.PlacedEquipment;
import org.openwcs.flow.repo.DeviceTaskRepository;
import org.openwcs.flow.repo.PlacedEquipmentRepository;
import org.openwcs.flow.repo.ScanStatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo DASHBOARD seeding (build.md §4.8) for flow-orchestrator. Backfills:
 *
 * <ul>
 *   <li>{@code scan_stat} rows for TODAY across a handful of scan nodes with a realistic no-read
 *       rate (mostly low, one node slightly elevated);</li>
 *   <li>one placed equipment marked faulted (a recent FAILED {@code device_task} on it) so the
 *       equipment-availability figure dips below 100%.</li>
 * </ul>
 *
 * <p>Lights /reports/automation-summary. Gated to demo mode (no-op-with-error / 409 when off);
 * strictly demo-only.
 */
@Service
public class FlowDashboardSeedService {

    private static final Logger log = LoggerFactory.getLogger(FlowDashboardSeedService.class);

    /** Scan nodes seeded today. The elevated node carries a higher no-read share. */
    private static final String[] NODES = {
        "INDUCT-1", "DIVERT-A", "DIVERT-B", "MERGE-1", "SCAN-TUNNEL-1", "SCAN-TUNNEL-2",
    };
    private static final String ELEVATED_NODE = "SCAN-TUNNEL-2";
    private static final int SCANS_PER_NODE = 60;
    private static final long RNG_SEED = 20260615L;

    private final ScanStatRepository scanStats;
    private final DeviceTaskRepository deviceTasks;
    private final PlacedEquipmentRepository placedEquipment;
    private final MasterDataClient masterData;

    public FlowDashboardSeedService(ScanStatRepository scanStats, DeviceTaskRepository deviceTasks,
            PlacedEquipmentRepository placedEquipment, MasterDataClient masterData) {
        this.scanStats = scanStats;
        this.deviceTasks = deviceTasks;
        this.placedEquipment = placedEquipment;
        this.masterData = masterData;
    }

    /**
     * Backfill automation-summary data for a warehouse.
     *
     * @throws IllegalStateException if demo mode is not enabled
     */
    @Transactional
    public DemoDashboardSeedResult seed(UUID warehouseId) {
        if (!masterData.demoEnabled()) {
            throw new IllegalStateException("Demo mode is not enabled (flow dashboard seed is demo-only).");
        }
        Random rnd = new Random(RNG_SEED);

        // 1) Today's scan-quality counters. The repository upserts onto CURRENT_DATE; each bump is
        //    one scan with a 0/1 no-read flag, so we approximate a target rate by flipping flags.
        int scansBumped = 0;
        for (String node : NODES) {
            // ~1% no-reads on normal nodes, ~6% on the one elevated node.
            int noReadEvery = node.equals(ELEVATED_NODE) ? 16 : 100;
            for (int s = 0; s < SCANS_PER_NODE; s++) {
                int noRead = (s % noReadEvery == noReadEvery - 1) ? 1 : 0;
                int unknown = (rnd.nextInt(200) == 0) ? 1 : 0;
                scanStats.bump(warehouseId, node, noRead, unknown);
                scansBumped++;
            }
        }

        // 2) Mark one placed equipment faulted. The fault query joins device_task.equipment_id to
        //    placed_equipment.equipment_id and checks the most-recent task is FAILED — so the
        //    placed equipment must carry an equipment_id, and the FAILED task must reuse it.
        int faulted = 0;
        List<PlacedEquipment> placed = placedEquipment.findByWarehouseId(warehouseId);
        PlacedEquipment target = placed.stream().filter(p -> p.getEquipmentId() != null).findFirst()
                .orElse(null);
        if (target == null) {
            // No demo placement yet — create a stand-in placed equipment so the KPI has a subject.
            PlacedEquipment demo = new PlacedEquipment();
            demo.setWarehouseId(warehouseId);
            demo.setEquipmentId(UUID.randomUUID());
            demo.setCode("DEMO-DASH-EQ-1");
            demo.setStatus("ACTIVE");
            target = placedEquipment.save(demo);
        }
        DeviceTask failed = new DeviceTask();
        failed.setWarehouseId(warehouseId);
        failed.setFamily("CONVEYOR");
        failed.setEquipmentId(target.getEquipmentId());
        failed.setCommand("CONVEY");
        failed.setPayload(Map.of("demo", true));
        failed.setStatus("FAILED");
        failed.setDetail("Demo: simulated device fault for the automation dashboard.");
        failed.setActor("demo");
        deviceTasks.save(failed);
        faulted = 1;

        log.info("demo dashboard seed for warehouse {}: bumped {} scans across {} nodes and marked"
                        + " {} equipment faulted because an admin requested flow dashboard demo data",
                warehouseId, scansBumped, NODES.length, faulted);
        return new DemoDashboardSeedResult(NODES.length, scansBumped, faulted);
    }
}
