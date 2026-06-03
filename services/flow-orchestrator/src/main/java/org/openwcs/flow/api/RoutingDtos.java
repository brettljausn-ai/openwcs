package org.openwcs.flow.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Request/response shapes for conveyor routing, route plans, and topology. */
public final class RoutingDtos {

    private RoutingDtos() {
    }

    /** A scan event from a conveyor adapter: a handling unit seen at a node. */
    public record ScanRequest(@NotNull UUID warehouseId, @NotBlank String node, @NotBlank String barcode) {
    }

    /**
     * The WCS routing decision for a scan.
     * <ul>
     *   <li>ROUTE — take {@code exitCode} toward {@code toNode} (next hop to the current target).</li>
     *   <li>COMPLETE — the HU has reached its final target; route plan done.</li>
     *   <li>HOLD — wait (e.g. a loop is at capacity); re-evaluated on the next scan.</li>
     *   <li>NO_ROUTE — no active route plan for this barcode.</li>
     *   <li>EXCEPTION — unknown node, unreachable target, etc. (see {@code detail}).</li>
     * </ul>
     */
    public record RoutingDecision(String action, String exitCode, String toNode, String targetReached,
                                  String currentTarget, String detail) {

        public static RoutingDecision route(String exitCode, String toNode, String currentTarget, String reached) {
            return new RoutingDecision("ROUTE", exitCode, toNode, reached, currentTarget, null);
        }

        public static RoutingDecision complete(String reached) {
            return new RoutingDecision("COMPLETE", null, null, reached, null, null);
        }

        public static RoutingDecision noRoute() {
            return new RoutingDecision("NO_ROUTE", null, null, null, null, "No active route plan for the barcode");
        }

        public static RoutingDecision exception(String detail) {
            return new RoutingDecision("EXCEPTION", null, null, null, null, detail);
        }

        /** A loop the HU would enter is at capacity; wait upstream and re-evaluate next scan. */
        public static RoutingDecision hold(String currentTarget, String detail) {
            return new RoutingDecision("HOLD", null, null, null, currentTarget, detail);
        }

        /** Loop full: diverted to the loop's overflow target instead of entering. */
        public static RoutingDecision overflow(String exitCode, String toNode, String currentTarget, String detail) {
            return new RoutingDecision("ROUTE", exitCode, toNode, null, currentTarget, detail);
        }
    }

    /** Register/replace a handling unit's ordered target node codes. */
    public record RouteRequest(@NotNull UUID warehouseId, @NotBlank String barcode,
                               @NotEmpty List<String> targets) {
    }

    public record RouteView(UUID warehouseId, String barcode, List<String> targets, int currentIndex,
                            String status, String detail) {
    }

    /** The whole conveyor graph for a warehouse — the load/save shape for the admin editor. */
    public record Topology(List<NodeDto> nodes, List<EdgeDto> edges, List<LoopDto> loops,
                           List<ControllerDto> controllers) {
    }

    /**
     * A conveyor controller (PLC) reached at {@code ipAddress}:{@code port}, hosting many nodes
     * that reference it by {@code code}.
     */
    public record ControllerDto(String code, String name, String ipAddress, Integer port) {
    }

    public record NodeDto(String code, String name, String hardwareAddress, Double posX, Double posY,
                          String loopCode, String controllerCode, String nodeAddress) {
    }

    public record EdgeDto(String fromCode, String toCode, String exitCode, Integer cost) {
    }

    /** A looping section: capacity + what to do when full (HOLD or OVERFLOW to a target). */
    public record LoopDto(String code, int maxHus, String whenFull, String overflowTarget) {
    }
}
