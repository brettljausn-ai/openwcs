package org.openwcs.inventory.api;

import java.time.LocalDate;
import java.util.UUID;

/** One storage block's fill level on one day (storage-density report; pct = occupied/total in %). */
public record StorageDensityRow(
        UUID blockId,
        LocalDate day,
        int occupiedCells,
        int totalCells,
        double pct) {
}
