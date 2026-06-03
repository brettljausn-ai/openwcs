package org.openwcs.slotting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the put-away scoring + hard constraints (no Spring). */
class PutawayScorerTest {

    private static final UUID SKU = UUID.randomUUID();
    private static final UUID OTHER_SKU = UUID.randomUUID();

    private static PutawayScorer.Candidate empty(UUID id, String aisle, double dist) {
        return new PutawayScorer.Candidate(id, aisle, 3, dist, 0, null, 0, 0, 9);
    }

    @Test
    void classAPrefersLocationNearestExit() {
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        var input = new PutawayScorer.Input(SKU, "A", true, 1.0, 0);
        var policy = new PutawayScorer.Policy(1, 0, 0, 0);

        var ranked = PutawayScorer.rank(input,
                List.of(empty(far, "A1", 10.0), empty(near, "A1", 1.0)), policy);

        assertThat(ranked.get(0).locationId()).isEqualTo(near);
    }

    @Test
    void classCPrefersDeepLocation() {
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        var input = new PutawayScorer.Input(SKU, "C", true, 1.0, 0);
        var policy = new PutawayScorer.Policy(1, 0, 0, 0);

        var ranked = PutawayScorer.rank(input,
                List.of(empty(near, "A1", 1.0), empty(far, "A1", 10.0)), policy);

        assertThat(ranked.get(0).locationId()).isEqualTo(far);
    }

    @Test
    void excludesLaneHoldingADifferentSku() {
        UUID occupied = UUID.randomUUID();
        var input = new PutawayScorer.Input(SKU, "B", true, 1.0, 0);
        var policy = new PutawayScorer.Policy(1, 1, 1, 1);

        var blocked = new PutawayScorer.Candidate(occupied, "A1", 3, 1.0, 1, OTHER_SKU, 0, 1, 3);
        assertThat(PutawayScorer.rank(input, List.of(blocked), policy)).isEmpty();
    }

    @Test
    void consolidationPrefersAPartialSameSkuLane() {
        UUID emptyLane = UUID.randomUUID();
        UUID sameSkuLane = UUID.randomUUID();
        var input = new PutawayScorer.Input(SKU, "B", true, 1.0, 1);
        var policy = new PutawayScorer.Policy(0, 1, 0, 0); // consolidation only

        var same = new PutawayScorer.Candidate(sameSkuLane, "A1", 3, 5.0, 1, SKU, 1, 1, 6);
        var ranked = PutawayScorer.rank(input, List.of(empty(emptyLane, "A2", 5.0), same), policy);

        assertThat(ranked.get(0).locationId()).isEqualTo(sameSkuLane);
    }

    @Test
    void maxAislePctCapExcludesOverConcentratedAisle() {
        UUID hot = UUID.randomUUID();   // aisle already holds 2 of this SKU's 2 HUs
        UUID fresh = UUID.randomUUID(); // a different aisle with none
        var input = new PutawayScorer.Input(SKU, "B", true, 0.5, 2);
        var policy = new PutawayScorer.Policy(0, 0, 1, 0);

        var hotCand = new PutawayScorer.Candidate(hot, "A1", 3, 1.0, 2, SKU, 2, 2, 6);
        var freshCand = new PutawayScorer.Candidate(fresh, "A2", 3, 1.0, 0, null, 0, 0, 6);

        var ranked = PutawayScorer.rank(input, List.of(hotCand, freshCand), policy);
        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).locationId()).isEqualTo(fresh);
    }

    @Test
    void balancePrefersTheEmptierAisle() {
        UUID busy = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();
        var input = new PutawayScorer.Input(SKU, "B", true, 1.0, 0);
        var policy = new PutawayScorer.Policy(0, 0, 0, 1); // balance only

        var busyCand = new PutawayScorer.Candidate(busy, "A1", 3, 1.0, 0, null, 0, 8, 9);
        var quietCand = new PutawayScorer.Candidate(quiet, "A2", 3, 1.0, 0, null, 0, 1, 9);

        var ranked = PutawayScorer.rank(input, List.of(busyCand, quietCand), policy);
        assertThat(ranked.get(0).locationId()).isEqualTo(quiet);
    }
}
