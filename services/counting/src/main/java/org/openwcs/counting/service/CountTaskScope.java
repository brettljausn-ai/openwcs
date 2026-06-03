package org.openwcs.counting.service;

import java.util.UUID;

/**
 * A single (location, SKU[, batch]) cell the operator must count within a task. Supplied at task
 * creation (the caller enumerates the cells in the scope); the engine snapshots each cell's expected
 * on-hand from inventory into the corresponding {@link org.openwcs.counting.domain.CountLine}.
 */
public record CountTaskScope(UUID locationId, UUID skuId, UUID batchId, String uomCode) {
}
