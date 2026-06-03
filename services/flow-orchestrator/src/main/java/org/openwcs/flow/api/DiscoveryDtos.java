package org.openwcs.flow.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Shapes for topology learning: observation ingest and discovery suggestions. */
public final class DiscoveryDtos {

    private DiscoveryDtos() {
    }

    /** A scan observation from a sniffer (defined-IP capture) or replay. */
    public record ObservationRequest(@NotNull UUID warehouseId, @NotBlank String node, @NotBlank String barcode,
                                     String sourceIp, Integer sourcePort, String observedAt) {
    }

    /** Inferred topology suggestions; {@code known} flags what already exists in the configured graph. */
    public record Discovery(List<DiscoveredNode> nodes, List<DiscoveredEdge> edges, List<DiscoveredTarget> targets,
                            List<DiscoveredController> controllers) {
    }

    public record DiscoveredNode(String code, long observedCount, String sourceIp, Integer sourcePort,
                                 boolean known) {
    }

    public record DiscoveredEdge(String fromCode, String toCode, long count, boolean known) {
    }

    public record DiscoveredTarget(String code, long terminalCount) {
    }

    /**
     * A controller (PLC) proposed from observations grouped by their source {@code ipAddress}:{@code port}.
     * {@code nodeCodes} are the nodes seen behind that endpoint; {@code known} flags whether a
     * controller already exists in the configured topology for that endpoint.
     */
    public record DiscoveredController(String code, String ipAddress, Integer port, List<String> nodeCodes,
                                       boolean known) {
    }
}
