package org.openwcs.counting.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.openwcs.counting.service.CountEntry;

/** Operator's counted quantities for a task's lines. */
public record SubmitCountsRequest(@NotEmpty List<Entry> entries) {

    public record Entry(@NotNull UUID countLineId, @NotNull BigDecimal countedQty) {
    }

    public List<CountEntry> toEntries() {
        return entries.stream().map(e -> new CountEntry(e.countLineId(), e.countedQty())).toList();
    }
}
