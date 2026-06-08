package org.openwcs.gtp.client;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Asks the slotting service where to put a tote back. Returns the currently-best storage location for
 * the handling unit (the scored put-away choice), or empty when slotting has no place for it.
 */
public interface SlottingClient {

    Optional<UUID> bestLocation(UUID warehouseId, UUID huId, UUID skuId, BigDecimal qty);
}
