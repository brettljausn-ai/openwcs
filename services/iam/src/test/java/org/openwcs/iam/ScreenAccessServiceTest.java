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
 * full-map replace semantics, empty-entry pruning, and per-user accessible-key resolution.
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

    @Test
    void replaceStoresAndReturnsOverrides() {
        screenAccess.replaceAll(Map.of(
                "transport", new ScreenAccessView(List.of("SUPERVISOR"), List.of("alice")),
                "settings", new ScreenAccessView(List.of("ADMIN"), List.of())));

        Map<String, ScreenAccessView> overrides = screenAccess.overrides();
        assertThat(overrides).containsKeys("transport", "settings");
        assertThat(overrides.get("transport").roles()).containsExactly("SUPERVISOR");
        assertThat(overrides.get("transport").users()).containsExactly("alice");
    }

    @Test
    void replaceIsAFullReplacementAndPrunesEmptyEntries() {
        screenAccess.replaceAll(Map.of("topology", new ScreenAccessView(List.of("ADMIN"), List.of())));
        assertThat(screenAccess.overrides()).containsKey("topology");

        // A subsequent replace without "topology", plus an empty entry that must be dropped.
        screenAccess.replaceAll(Map.of(
                "slotting", new ScreenAccessView(List.of("SUPERVISOR"), List.of()),
                "processes", new ScreenAccessView(List.of(), List.of())));

        Map<String, ScreenAccessView> overrides = screenAccess.overrides();
        assertThat(overrides).containsKey("slotting");
        assertThat(overrides).doesNotContainKeys("topology", "processes");
    }

    @Test
    void accessibleKeysResolvePerUserWithAdminBypass() {
        screenAccess.replaceAll(Map.of(
                "transport", new ScreenAccessView(List.of("SUPERVISOR"), List.of("bob")),
                "settings", new ScreenAccessView(List.of("ADMIN"), List.of())));

        assertThat(screenAccess.accessibleKeys(List.of("SUPERVISOR"), "carol"))
                .containsExactly("transport");
        assertThat(screenAccess.accessibleKeys(List.of("OPERATOR"), "bob"))
                .containsExactly("transport"); // via user allow-list
        assertThat(screenAccess.accessibleKeys(List.of("ADMIN"), "admin"))
                .containsExactlyInAnyOrder("transport", "settings"); // admin bypass
        assertThat(screenAccess.accessibleKeys(List.of("VIEWER"), "nobody")).isEmpty();
    }
}
