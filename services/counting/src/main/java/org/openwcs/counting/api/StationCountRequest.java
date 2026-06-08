package org.openwcs.counting.api;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Operator's single at-station blind count for a line (they never see the system qty). */
public record StationCountRequest(@NotNull BigDecimal countedQty) {
}
