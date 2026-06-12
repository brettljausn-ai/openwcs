package org.openwcs.slotting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.slotting.client.MasterDataClient.StorageLocation;

/**
 * Velocity-to-exit needs a usable distance per candidate. Generated racks carry cell
 * coordinates but no maintained {@code distance_to_exit}; without the coordinate fallback every
 * candidate collapses to distance 0 and placement degrades to list order (the observed
 * "one level on either side, then into the aisle" fill) instead of "fast movers near the port".
 */
class BlockOccupancyDistanceTest {

    private static StorageLocation cell(int posX, int posY, BigDecimal explicit) {
        return new StorageLocation(UUID.randomUUID(), "L", "STORAGE", "ASRS_SLOT",
                "A1", posY, 1, posX, posY, 1, explicit, null, "ACTIVE", null);
    }

    @Test
    void derivesDistanceFromCellCoordinatesWhenNoneIsMaintained() {
        // port assumed at position 1, ground level, lane face → (x-1) + (y-1) + (z-1)
        assertThat(BlockOccupancy.effectiveDistanceToExit(cell(1, 1, null))).isEqualTo(0.0);
        assertThat(BlockOccupancy.effectiveDistanceToExit(cell(5, 1, null))).isEqualTo(4.0);
        assertThat(BlockOccupancy.effectiveDistanceToExit(cell(3, 4, null))).isEqualTo(5.0);
    }

    @Test
    void explicitDistanceToExitAlwaysWins() {
        assertThat(BlockOccupancy.effectiveDistanceToExit(cell(9, 9, BigDecimal.valueOf(2.5)))).isEqualTo(2.5);
    }

    @Test
    void fastMoverLandsNearThePortOnCoordinateOnlyRacks() {
        StorageLocation near = cell(1, 1, null);
        StorageLocation mid = cell(6, 1, null);
        StorageLocation far = cell(12, 3, null);

        List<PutawayScorer.Candidate> candidates =
                BlockOccupancy.candidates(UUID.randomUUID(), List.of(far, mid, near), List.of());
        PutawayScorer.Input in = new PutawayScorer.Input(
                UUID.randomUUID(), Set.of(UUID.randomUUID()), "A", true, 1.0, 0);
        PutawayScorer.Policy velocityOnly = new PutawayScorer.Policy(1.0, 0.0, 0.0, 0.0);

        List<PutawayScorer.Result> ranked = PutawayScorer.rank(in, candidates, velocityOnly);

        assertThat(ranked.get(0).locationId()).isEqualTo(near.id());
        assertThat(ranked.get(ranked.size() - 1).locationId()).isEqualTo(far.id());
    }
}
