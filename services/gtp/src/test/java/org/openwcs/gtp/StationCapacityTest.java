package org.openwcs.gtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.gtp.api.CreateStationRequest;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.repo.GtpStationRepository;
import org.openwcs.gtp.service.GtpStationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Per-station in-transit HU caps against PostgreSQL 16: a new station gets the schema defaults
 * (4 PICKING / 2 OTHER), an admin can set both caps and read them back, and negative caps are
 * rejected. This is config only; the enforcement lives elsewhere.
 */
@SpringBootTest
@Testcontainers
class StationCapacityTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    GtpStationService service;

    @Autowired
    GtpStationRepository stations;

    @Test
    void newStationUsesDefaultCapsAndCapacityCanBeSetAndReadBack() {
        GtpStation created = service.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-CAP", null, "ORDER_LOCATION", List.of("PICKING"), List.of()));

        // Schema defaults.
        assertThat(created.getMaxInTransitPicking()).isEqualTo(4);
        assertThat(created.getMaxInTransitOther()).isEqualTo(2);

        GtpStation updated = service.setCapacity(created.getId(), 8, 3);
        assertThat(updated.getMaxInTransitPicking()).isEqualTo(8);
        assertThat(updated.getMaxInTransitOther()).isEqualTo(3);

        GtpStation reloaded = stations.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getMaxInTransitPicking()).isEqualTo(8);
        assertThat(reloaded.getMaxInTransitOther()).isEqualTo(3);
    }

    @Test
    void capacityRejectsNegativeCaps() {
        GtpStation station = service.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-CAP-NEG", null, "PUT_WALL", List.of("PICKING"), List.of()));

        assertThatThrownBy(() -> service.setCapacity(station.getId(), -1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setCapacity(station.getId(), 2, -5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
