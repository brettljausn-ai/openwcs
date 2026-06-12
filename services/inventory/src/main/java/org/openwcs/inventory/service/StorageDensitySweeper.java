package org.openwcs.inventory.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.openwcs.inventory.client.MasterDataClient;
import org.openwcs.inventory.client.MasterDataUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily storage-density sweep: snapshots every storage block of every warehouse (one row per
 * block per day, idempotent upsert) so the Reporting screen has a 90-day occupancy history.
 * ShedLock-guarded so a scaled-out inventory service sweeps once per day, not once per replica.
 * A missed sweep (e.g. master-data down) self-heals: the report endpoint snapshots today on
 * demand. Disabled in tests via {@code openwcs.inventory.density-sweep.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "openwcs.inventory.density-sweep.enabled", havingValue = "true",
        matchIfMissing = true)
public class StorageDensitySweeper {

    private static final Logger log = LoggerFactory.getLogger(StorageDensitySweeper.class);

    private final StorageDensityService density;
    private final MasterDataClient masterData;

    public StorageDensitySweeper(StorageDensityService density, MasterDataClient masterData) {
        this.density = density;
        this.masterData = masterData;
    }

    /** Runs shortly after UTC midnight so each day gets a fresh end-of-day-ish baseline. */
    @Scheduled(cron = "${openwcs.inventory.density-sweep.cron:0 10 0 * * *}", zone = "UTC")
    @SchedulerLock(name = "storage-density-sweep", lockAtMostFor = "PT30M")
    public void sweep() {
        LocalDate day = StorageDensityService.today();
        List<UUID> warehouses;
        try {
            warehouses = masterData.warehouseIds();
        } catch (MasterDataUnavailableException e) {
            log.warn("storage-density sweep skipped: master-data is unreachable ({}); the report"
                    + " endpoint will snapshot today on demand instead", e.getMessage());
            return;
        }
        int blocks = 0;
        for (UUID warehouseId : warehouses) {
            try {
                blocks += density.snapshotWarehouse(warehouseId, day);
            } catch (RuntimeException e) {
                log.warn("storage-density sweep failed for warehouse {} ({}); continuing with the"
                        + " remaining warehouses, the report endpoint self-heals today on demand",
                        warehouseId, e.toString());
            }
        }
        log.info("storage-density sweep done: {} blocks across {} warehouses snapshotted for {}",
                blocks, warehouses.size(), day);
    }
}
