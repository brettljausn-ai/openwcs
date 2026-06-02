package org.openwcs.allocation.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Request to allocate (and cube) an order. */
public record AllocateOrderRequest(
        @NotNull String orderRef,
        @NotNull UUID warehouseId,
        /** Optional override of the warehouse cubing mode: APP | ONE_TO_ONE. */
        String cubingMode,
        @NotEmpty List<Line> lines,
        /** Host-supplied cube plan, used when cubing mode is ONE_TO_ONE. */
        List<CubeInstruction> cubeInstructions) {

    public record Line(@NotNull Integer lineNo, @NotNull UUID skuId, @NotNull @Positive BigDecimal qty) {
    }

    public record CubeInstruction(@NotNull String shipperCode, List<Content> contents) {
    }

    public record Content(@NotNull UUID skuId, @NotNull BigDecimal qty) {
    }
}
