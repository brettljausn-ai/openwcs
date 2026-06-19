package org.openwcs.gtp.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Drives pick-to-light (PTL) hardware for a station's put-list. {@code illuminate} lights a put
 * location with its quantity on present; {@code clear} turns it off on confirm/close. Both go to the
 * PTL adapter (or the equipment emulator when hardware-emulator mode is on) via the uniform device
 * contract. Calls are best-effort by contract: implementations must never throw on a hardware/HTTP
 * failure so the pick cycle is never blocked.
 */
public interface LightControlClient {

    /** Light {@code lightId} on {@code equipmentId} with {@code qty} (green). Best-effort. */
    void illuminate(UUID warehouseId, String equipmentId, String lightId, BigDecimal qty);

    /** Clear (turn off) {@code lightId} on {@code equipmentId}. Best-effort. */
    void clear(UUID warehouseId, String equipmentId, String lightId);
}
