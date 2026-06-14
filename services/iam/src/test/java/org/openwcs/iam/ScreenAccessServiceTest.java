package org.openwcs.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openwcs.iam.api.ScreenAccessView;
import org.openwcs.iam.service.ScreenAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots IAM against PostgreSQL 16 (Flyway creates the screen_access store). Verifies the
 * full-map replace semantics, empty-entry pruning, and per-user effective-level resolution
 * (off / read / write).
 */
@SpringBootTest
@Testcontainers
class ScreenAccessServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    ScreenAccessService screenAccess;

    private static ScreenAccessView view(Map<String, String> roles, Map<String, String> users) {
        return new ScreenAccessView(roles, users);
    }

    @Test
    void replaceStoresAndReturnsOverridesWithLevels() {
        screenAccess.replaceAll(Map.of(
                "transport", view(Map.of("SUPERVISOR", "write", "VIEWER", "read"), Map.of("alice", "read")),
                "settings", view(Map.of("ADMIN", "write"), Map.of())));

        Map<String, ScreenAccessView> overrides = screenAccess.overrides();
        assertThat(overrides).containsKeys("transport", "settings");
        assertThat(overrides.get("transport").roles())
                .containsEntry("SUPERVISOR", "write")
                .containsEntry("VIEWER", "read");
        assertThat(overrides.get("transport").users()).containsEntry("alice", "read");
    }

    @Test
    void replaceIsAFullReplacementAndPrunesEmptyEntries() {
        screenAccess.replaceAll(Map.of("topology", view(Map.of("ADMIN", "write"), Map.of())));
        assertThat(screenAccess.overrides()).containsKey("topology");

        // A subsequent replace without "topology", plus an empty entry that must be dropped.
        screenAccess.replaceAll(Map.of(
                "slotting", view(Map.of("SUPERVISOR", "write"), Map.of()),
                "processes", view(Map.of(), Map.of())));

        Map<String, ScreenAccessView> overrides = screenAccess.overrides();
        assertThat(overrides).containsKey("slotting");
        assertThat(overrides).doesNotContainKeys("topology", "processes");
    }

    @Test
    void offLevelEntriesAreDropped() {
        // "off"/unknown levels are not OFF rows — they are simply not stored.
        screenAccess.replaceAll(Map.of(
                "transport", view(Map.of("SUPERVISOR", "write", "OPERATOR", "off"), Map.of())));
        assertThat(screenAccess.overrides().get("transport").roles())
                .containsOnlyKeys("SUPERVISOR");
    }

    @Test
    void effectiveLevelsResolvePerUserTakingTheStrongest() {
        screenAccess.replaceAll(Map.of(
                "transport", view(Map.of("SUPERVISOR", "write", "VIEWER", "read"), Map.of("bob", "read")),
                "settings", view(Map.of("ADMIN", "write"), Map.of())));

        // A supervisor gets write on transport; nothing on settings (no matching role).
        assertThat(screenAccess.effectiveLevels(List.of("SUPERVISOR"), "carol"))
                .containsEntry("transport", "write")
                .doesNotContainKey("settings");
        // A viewer gets read on transport.
        assertThat(screenAccess.effectiveLevels(List.of("VIEWER"), "dave"))
                .containsEntry("transport", "read");
        // bob's per-user read plus a viewer role's read = read; a writing role would win if present.
        assertThat(screenAccess.effectiveLevels(List.of("OPERATOR"), "bob"))
                .containsEntry("transport", "read");
        // Strongest wins: a user listed read but also holding a write role gets write.
        assertThat(screenAccess.effectiveLevels(List.of("SUPERVISOR"), "bob"))
                .containsEntry("transport", "write");
        // No match anywhere → empty.
        assertThat(screenAccess.effectiveLevels(List.of("OPERATOR"), "nobody")).isEmpty();
    }
}
