package org.openwcs.counting.service;

import java.math.BigDecimal;
import java.util.UUID;

/** An operator's counted quantity for one count line. */
public record CountEntry(UUID countLineId, BigDecimal countedQty) {
}
