package org.openwcs.flow.api;

import java.util.List;

/**
 * Live-twin telemetry read models for the hardware visualisation (execution-layer item 3): AMR fleet
 * positions and AutoStore grid/port activity, mirroring the tote-paths "visu master" pattern. Each
 * carries {@code serverTimeMs} so the frontend can anchor its render clock, and degrades to an empty
 * list/zeroed grid when the emulator is off or unreachable (best-effort).
 */
public final class TwinTelemetryDtos {

    private TwinTelemetryDtos() {
    }

    /**
     * @param serverTimeMs server clock at response time (frontend render-clock anchor).
     * @param amrs         one entry per active AMR robot.
     */
    public record AmrFleet(long serverTimeMs, List<Amr> amrs) {
    }

    /**
     * An AMR robot in world XZ metres.
     *
     * @param status IDLE | MOVING | FAULTED.
     * @param huCode the carried handling unit's code, or null when the robot is empty.
     */
    public record Amr(String amrId, double x, double z, String status, String huCode) {
    }

    /**
     * @param serverTimeMs server clock at response time.
     * @param grid         grid dimensions + fill.
     * @param ports        one entry per AutoStore port.
     */
    public record Autostore(long serverTimeMs, Grid grid, List<Port> ports) {
    }

    /** AutoStore grid dimensions and fill. */
    public record Grid(int columns, int rows, int occupiedBins, int totalBins) {
    }

    /**
     * An AutoStore port in world XZ metres.
     *
     * @param busy   whether the port is currently presenting/working a bin.
     * @param huCode the handling unit currently at the port, or null.
     */
    public record Port(String portId, double x, double z, boolean busy, String huCode) {
    }

    /**
     * @param serverTimeMs server clock at response time (frontend render-clock anchor).
     * @param cranes       one entry per ASRS crane.
     */
    public record AsrsCranes(long serverTimeMs, List<Crane> cranes) {
    }

    /**
     * An ASRS crane in world coordinates: x/z metres (aisle floor position), y the lift height metres.
     *
     * @param status IDLE | MOVING | FAULTED.
     * @param huCode the carried handling unit's code, or null when the crane is empty.
     */
    public record Crane(String craneId, double x, double y, double z, String status, String huCode) {
    }
}
