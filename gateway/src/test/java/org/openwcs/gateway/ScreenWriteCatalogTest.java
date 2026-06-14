package org.openwcs.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openwcs.gateway.ScreenAccessResolver.Override;
import org.openwcs.gateway.ScreenWriteCatalog.Level;

/** Path ownership + effective-level resolution for gateway read-vs-write screen enforcement. */
class ScreenWriteCatalogTest {

    private final ScreenWriteCatalog catalog = new ScreenWriteCatalog();

    @Test
    void mapsOwnedWritePathsAndIgnoresUnmappedOnes() {
        assertThat(catalog.screenForPath("/api/master-data/skus")).isEqualTo("master-data:skus");
        assertThat(catalog.screenForPath("/api/master-data/skus/abc-123")).isEqualTo("master-data:skus");
        assertThat(catalog.screenForPath("/api/counting/tasks/1/lines")).isEqualTo("counting");
        assertThat(catalog.screenForPath("/api/flow/automation/topology/project")).isEqualTo("topology");
        assertThat(catalog.screenForPath("/api/iam/screen-access")).isEqualTo("access-control");
        // Shared / out-of-band surfaces are intentionally unmapped (fail-open).
        assertThat(catalog.screenForPath("/api/orders/abc")).isNull();
        assertThat(catalog.screenForPath("/api/flow/twin/tote-paths")).isNull();
    }

    @Test
    void defaultsGiveSupervisorWriteOnMasterDataButNothingToOperator() {
        Map<String, Override> noOverrides = Map.of();
        assertThat(catalog.effectiveLevel("master-data:skus", List.of("SUPERVISOR"), "sue", noOverrides))
                .isEqualTo(Level.WRITE);
        // OPERATOR isn't a default role on master-data → OFF (null).
        assertThat(catalog.effectiveLevel("master-data:skus", List.of("OPERATOR"), "ollie", noOverrides))
                .isNull();
        // OPERATOR is a default role on counting → write.
        assertThat(catalog.effectiveLevel("counting", List.of("OPERATOR"), "ollie", noOverrides))
                .isEqualTo(Level.WRITE);
    }

    @Test
    void adminDatabaseIsAnAccessRoute_readSufficesWhenGranted() {
        assertThat(catalog.screenForAccessPath("/api/master-data/admin/db/schemas")).isEqualTo("admin-database");
        assertThat(catalog.screenForAccessPath("/api/master-data/admin/db/query")).isEqualTo("admin-database");
        // The console is SELECT-only, so it is not a write route (READ is enough to use it).
        assertThat(catalog.screenForPath("/api/master-data/admin/db/query")).isNull();

        Map<String, Override> noOverrides = Map.of();
        // ADMIN by default; nobody else has access until granted.
        assertThat(catalog.effectiveLevel("admin-database", List.of("SUPERVISOR"), "sue", noOverrides)).isNull();
        // Granting a supervisor read is enough to reach it.
        Map<String, Override> granted = Map.of(
                "admin-database", new Override(Map.of("SUPERVISOR", "read"), Map.of()));
        assertThat(catalog.effectiveLevel("admin-database", List.of("SUPERVISOR"), "sue", granted))
                .isEqualTo(Level.READ);
    }

    @Test
    void overrideReplacesDefaultsAndTakesTheStrongestMatch() {
        // Supervisor downgraded to read on SKUs.
        Map<String, Override> ro = Map.of(
                "master-data:skus", new Override(Map.of("SUPERVISOR", "read"), Map.of("vip", "write")));
        assertThat(catalog.effectiveLevel("master-data:skus", List.of("SUPERVISOR"), "sue", ro))
                .isEqualTo(Level.READ);
        // A per-user write grant beats the read role.
        assertThat(catalog.effectiveLevel("master-data:skus", List.of("SUPERVISOR"), "vip", ro))
                .isEqualTo(Level.WRITE);
        // Someone with no matching role/user on an overridden screen → OFF.
        assertThat(catalog.effectiveLevel("master-data:skus", List.of("OPERATOR"), "ollie", ro))
                .isNull();
    }
}
