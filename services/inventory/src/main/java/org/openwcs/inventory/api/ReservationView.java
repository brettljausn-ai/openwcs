package org.openwcs.inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.openwcs.inventory.domain.Reservation;

/** Read model for a reservation. */
public record ReservationView(
        UUID id,
        UUID warehouseId,
        UUID skuId,
        UUID batchId,
        UUID locationId,
        UUID huId,
        String orderRef,
        UUID correlationId,
        BigDecimal qty,
        String status,
        Instant expiresAt,
        Instant createdAt) {

    public static ReservationView from(Reservation r) {
        return new ReservationView(
                r.getId(), r.getWarehouseId(), r.getSkuId(), r.getBatchId(), r.getLocationId(),
                r.getHuId(), r.getOrderRef(), r.getCorrelationId(), r.getQty(), r.getStatus(),
                r.getExpiresAt(), r.getCreatedAt());
    }
}
