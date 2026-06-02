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
                CubingEngine.appCube(List.of(new CubingEngine.Item(1, sku, 3, 0d, 400d)), List.of(shipper));

        assertThat(assignments).hasSize(2);
        long totalUnits = assignments.stream()
                .flatMap(a -> a.contents().stream())
                .mapToLong(c -> c.qty().longValue())
                .sum();
        assertThat(totalUnits).isEqualTo(3);
        assertThat(assignments.get(0).grossWeightG()).isEqualByComparingTo("900"); // 100 tare + 800
        // Each carton has a stable identity and the content traces back to the order line.
        assertThat(assignments).allSatisfy(a -> assertThat(a.shipperUnitId()).isNotNull());
        assertThat(assignments.get(0).contents().get(0).lineNo()).isEqualTo(1);
    }

    @Test
    void cubesIntoLargestThenSmallerCartonAndSplitsTheLine() {
        // Two carton sizes; volume is the constraint (no weight cap). BIG holds 4 units, SMALL holds 2.
        ShipperDef big = new ShipperDef(UUID.randomUUID(), "CARTON-BIG",
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                BigDecimal.ZERO, BigDecimal.ONE, null, "ACTIVE");
        ShipperDef small = new ShipperDef(UUID.randomUUID(), "CARTON-SMALL",
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), BigDecimal.valueOf(50),
                BigDecimal.ZERO, BigDecimal.ONE, null, "ACTIVE");
        UUID sku = UUID.randomUUID();

        // One order line, 5 units @ 250_000 mm³: BIG takes 4, the remaining 1 downsizes to SMALL.
        // Shippers passed smallest-first to prove the engine ranks them itself.
        List<ShipperAssignment> assignments = CubingEngine.appCube(
                List.of(new CubingEngine.Item(7, sku, 5, 250_000d, 0d)), List.of(small, big));

        assertThat(assignments).hasSize(2);
        assertThat(assignments.get(0).shipperCode()).isEqualTo("CARTON-BIG");
        assertThat(assignments.get(0).seqNo()).isEqualTo(1);
        assertThat(assignments.get(1).shipperCode()).isEqualTo("CARTON-SMALL"); // smaller carton for the remainder
        assertThat(assignments.get(1).seqNo()).isEqualTo(2);

        // The single line (7) is split across both cartons, 4 + 1 = 5 units, and stays traceable.
        assertThat(assignments).allSatisfy(a -> {
            assertThat(a.shipperUnitId()).isNotNull();
            assertThat(a.contents()).allSatisfy(c -> assertThat(c.lineNo()).isEqualTo(7));
        });
        assertThat(assignments.get(0).contents().get(0).qty()).isEqualByComparingTo("4");
        assertThat(assignments.get(1).contents().get(0).qty()).isEqualByComparingTo("1");
        // Distinct physical cartons → distinct identities.
        assertThat(assignments.get(0).shipperUnitId()).isNotEqualTo(assignments.get(1).shipperUnitId());
    }
}
