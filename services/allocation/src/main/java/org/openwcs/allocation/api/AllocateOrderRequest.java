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
        List<CubeInstruction> cubeInstructions,
        /** Shared dispatch-label context (ship-to, service, route, template); per-shipper barcode is fetched from the host. */
        Dispatch dispatch,
        /**
         * Allow-short mode (null/false = strict): a line with insufficient stock allocates the
         * available quantity (possibly zero) instead of failing the whole order; reservations
         * are kept and only the allocated quantities are cubed (status FULFILLABLE_SHORT).
         */
        Boolean allowShort) {

    /** Allow-short mode resolved with its default (strict). */
    public boolean shortAllowed() {
        return Boolean.TRUE.equals(allowShort);
    }

    public record Line(@NotNull Integer lineNo, @NotNull UUID skuId, @NotNull @Positive BigDecimal qty) {
    }

    public record CubeInstruction(@NotNull String shipperCode, List<Content> contents) {
    }

    public record Content(@NotNull Integer lineNo, @NotNull UUID skuId, @NotNull BigDecimal qty) {
    }

    /** Dispatch-label context for the order; allocation builds a per-shipper label from it. */
    public record Dispatch(ShipTo shipTo, String serviceCode, String routeCode, String labelTemplateCode) {
    }

    public record ShipTo(String name, String line1, String line2, String city, String region,
                         String postcode, String country, String contact, String phone) {
    }
}
