package org.openwcs.gtp.client;

import java.util.Optional;
import java.util.UUID;

/**
 * Read seam onto the inventory HU registry: resolve a handling unit's type so the
 * single-SKU-per-compartment rule can check the target tote's compartment count.
 */
public interface InventoryClient {

    /** The {@code huTypeId} of a registered handling unit, if resolvable. */
    Optional<UUID> huTypeOf(UUID huId);
}
