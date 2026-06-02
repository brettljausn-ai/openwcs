package org.openwcs.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.allocation.client.MasterDataClient.ShipperDef;
import org.openwcs.allocation.client.MasterDataClient.UomDef;
import org.openwcs.allocation.domain.ShipperAssignment;
import org.openwcs.allocation.service.CubingEngine;
import org.openwcs.allocation.service.PickBreakdown;

/** Pure-logic tests for the pick-type breakdown and the cubing engine (no Spring/IO). */
class AllocationEngineTest {

    @Test
    void splitsIntoCasesThenEaches() {
        assertThat(PickBreakdown.split(30, List.of("CASE", "EACH"), 12))
                .containsEntry("CASE", 2)
                .containsEntry("EACH", 6);
    }

    @Test
    void fullCasesLeaveNoEaches() {
        assertThat(PickBreakdown.split(24, List.of("CASE", "EACH"), 12))
                .containsEntry("CASE", 2)
                .doesNotContainKey("EACH");
    }

    @Test
    void allEachesWhenCaseNotAllowed() {
        assertThat(PickBreakdown.split(30, List.of("EACH"), 12))
                .containsEntry("EACH", 30)
                .doesNotContainKey("CASE");
    }

    @Test
    void caseSizeReadFromCaseUom() {
        List<UomDef> uoms = List.of(
                new UomDef(UUID.randomUUID(), "EACH", null, null, null, null, null, null, true),
                new UomDef(UUID.randomUUID(), "CASE", BigDecimal.valueOf(12), null, null, null, null, null, false));
        assertThat(PickBreakdown.caseSize(uoms)).isEqualTo(12);
    }

    @Test
    void batchMergeSumsSameSkuLocationAndKeepsReservations() {
        UUID loc = UUID.randomUUID();
        UUID skuA = UUID.randomUUID();
        UUID skuB = UUID.randomUUID();
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        UUID r3 = UUID.randomUUID();

        var merged = org.openwcs.allocation.service.BatchPlanner.merge(List.of(
                new org.openwcs.allocation.service.BatchPlanner.PickItem(loc, skuA, BigDecimal.ONE, r1),
                new org.openwcs.allocation.service.BatchPlanner.PickItem(loc, skuA, BigDecimal.ONE, r2),
                new org.openwcs.allocation.service.BatchPlanner.PickItem(loc, skuB, BigDecimal.ONE, r3)));

        assertThat(merged).hasSize(2);
        var skuALine = merged.stream().filter(m -> m.skuId().equals(skuA)).findFirst().orElseThrow();
        assertThat(skuALine.qty()).isEqualByComparingTo("2");
        assertThat(skuALine.reservationIds()).containsExactlyInAnyOrder(r1, r2);
    }

    @Test
    void cubingOpensNewShipperWhenWeightExceeded() {
        // Volume unconstrained (null dims); net weight cap = maxWeight - tare = 1000 - 100 = 900.
        ShipperDef shipper = new ShipperDef(
                UUID.randomUUID(), "TOTE-A", null, null, null,
                BigDecimal.valueOf(100), BigDecimal.ONE, BigDecimal.valueOf(1000), "ACTIVE");
        UUID sku = UUID.randomUUID();
        // 3 units @ 400g: 2 fit (800 ≤ 900), the 3rd opens a second shipper.
        List<ShipperAssignment> assignments =
                CubingEngine.appCube(List.of(new CubingEngine.Item(sku, 3, 0d, 400d)), shipper);

        assertThat(assignments).hasSize(2);
        long totalUnits = assignments.stream()
                .flatMap(a -> a.contents().stream())
                .mapToLong(c -> c.qty().longValue())
                .sum();
        assertThat(totalUnits).isEqualTo(3);
        assertThat(assignments.get(0).grossWeightG()).isEqualByComparingTo("900"); // 100 tare + 800
    }
}
