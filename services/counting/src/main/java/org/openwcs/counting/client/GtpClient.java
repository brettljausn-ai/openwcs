package org.openwcs.counting.client;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Read/write seam onto the GTP service for ASRS count-tote routing: find a goods-to-person station
 * that can take stock-count work, and enqueue a handling unit (tote) to it in {@code STOCK_COUNT}
 * mode.
 */
public interface GtpClient {

    /**
     * An ACTIVE station accepting work whose supported modes include {@code STOCK_COUNT}, if any.
     */
    Optional<UUID> findActiveCountingStation(UUID warehouseId);

    /** Pin a handling unit to a station's stock-count queue. */
    void enqueue(UUID stationId, EnqueueRequest request);

    /**
     * Queue request: the HU/tote to present, the SKU on it, qty, the operating mode, the equipment
     * family it is retrieved from, an optional travel distance ({@code null} = immediate), and the
     * count task + line this tote belongs to (so the station can drive the at-station count).
     */
    record EnqueueRequest(
            UUID huId,
            String huCode,
            UUID skuId,
            String skuCode,
            BigDecimal qty,
            String mode,
            String family,
            BigDecimal distanceM,
            UUID countTaskId,
            UUID countLineId) {
    }
}
