package org.openwcs.flow.api;

import java.util.List;
import java.util.UUID;

/**
 * Live-twin tote paths (the "visu master" read model). The backend, which owns the routing graph
 * and the scan trace, resolves each moving tote's ACTUAL traversed-node polyline in world XZ metres
 * plus the timestamps it was seen at each node, so the frontend renders by walking that polyline and
 * never guesses which belt a position is on (the projection ambiguity that made totes jump to a
 * conveyor's start at every divert).
 */
public final class TwinPathDtos {

    private TwinPathDtos() {
    }

    /**
     * @param serverNowMs the server clock at response time — the frontend anchors its delayed render
     *                    clock to this so server/client skew cannot push sampling out of the buffer.
     * @param totes       one entry per in-transit / recirculating handling unit.
     */
    public record TwinPaths(long serverNowMs, List<TotePath> totes) {
    }

    /**
     * @param waypoints the traversed nodes in order, each with its world position and the millisecond
     *                  timestamp it was scanned there. Consecutive waypoints are graph-adjacent, so a
     *                  straight segment between them IS the belt section (no projection needed).
     * @param next      the routed-to node the tote is heading for but has not reached yet (the lead
     *                  edge the frontend dead-reckons along on a buffer underrun), or null on arrival.
     */
    public record TotePath(UUID huId, String huCode, String state, List<Waypoint> waypoints, Waypoint next) {
    }

    /** A point on the path: node {@code code}, world {@code x}/{@code z} metres, scan time {@code tMs}
     *  (null for the not-yet-reached {@code next} node). */
    public record Waypoint(String code, double x, double z, Long tMs) {
    }
}
