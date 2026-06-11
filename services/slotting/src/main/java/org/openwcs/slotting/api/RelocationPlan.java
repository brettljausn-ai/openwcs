package org.openwcs.slotting.api;

import java.util.List;

/**
 * The dig-out plan for a blocked retrieve (ADR 0009), ordered front-most blocker first (ascending
 * cell Z — the shuttle must take the aisle-face tote before it can reach deeper ones). Empty steps
 * mean the channel is clear — unless {@code blocked} is set, which means a blocker exists but no
 * valid relocation target was found (the caller should log it rather than retrieve).
 */
public record RelocationPlan(List<RelocationStep> steps, boolean blocked) {

    /** A clear channel: nothing to relocate, the retrieve can go out. */
    public static RelocationPlan clear() {
        return new RelocationPlan(List.of(), false);
    }

    /** A blocker exists but cannot be placed anywhere: unplannable, not clear. */
    public static RelocationPlan unplannable() {
        return new RelocationPlan(List.of(), true);
    }
}
