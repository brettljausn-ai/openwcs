package org.openwcs.counting.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.openwcs.counting.domain.CountSchedule;

/** Request body to create an ABC-cadence count schedule. */
public record CreateScheduleRequest(
        @NotNull UUID warehouseId,
        @NotBlank String name,
        @NotBlank String scopeType,
        UUID scopeRef,
        String abcClass,
        String countType,
        @Positive int cadenceDays,
        BigDecimal tolerance,
        Instant nextDueAt) {

    public CountSchedule toEntity() {
        CountSchedule s = new CountSchedule();
        s.setWarehouseId(warehouseId);
        s.setName(name);
        s.setScopeType(scopeType);
        s.setScopeRef(scopeRef);
        s.setAbcClass(abcClass);
        if (countType != null) {
            s.setCountType(countType);
        }
        s.setCadenceDays(cadenceDays);
        if (tolerance != null) {
            s.setTolerance(tolerance);
        }
        if (nextDueAt != null) {
            s.setNextDueAt(nextDueAt);
        }
        return s;
    }
}
