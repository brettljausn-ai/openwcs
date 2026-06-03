package org.openwcs.gtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.gtp.api.AddNodeRequest;
import org.openwcs.gtp.api.CreateStationRequest;
import org.openwcs.gtp.api.NotFoundException;
import org.openwcs.gtp.api.UpdateNodeRequest;
import org.openwcs.gtp.api.UpdateStationRequest;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.StationNode;
import org.openwcs.gtp.repo.GtpStationRepository;
import org.openwcs.gtp.repo.StationNodeRepository;
import org.openwcs.gtp.service.GtpStationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Admin station-configuration CRUD against PostgreSQL 16: creating a station with a display name,
 * editing its code/name/mode/supported-modes (with code-uniqueness + PICKING-retention guarantees),
 * editing and removing nodes, and deleting a station (cascading to its nodes).
 */
@SpringBootTest
@Testcontainers
class StationConfigCrudTest {

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

    @Autowired
    StationNodeRepository nodes;

    @Test
    void createsStationWithNameThenUpdatesItsConfiguration() {
        UUID wh = UUID.randomUUID();
        GtpStation created = service.createStation(new CreateStationRequest(
                wh, "GTP-1", "Aisle 1 Put-wall", "PUT_WALL", List.of("PICKING"), List.of()));

        assertThat(created.getName()).isEqualTo("Aisle 1 Put-wall");
        assertThat(created.getMode()).isEqualTo("PUT_WALL");

        GtpStation updated = service.updateStation(created.getId(), new UpdateStationRequest(
                "GTP-1A", "Aisle 1 Order-locations", "ORDER_LOCATION", "INACTIVE",
                List.of("PICKING", "QC")));

        GtpStation reloaded = stations.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getCode()).isEqualTo("GTP-1A");
        assertThat(reloaded.getName()).isEqualTo("Aisle 1 Order-locations");
        assertThat(reloaded.getMode()).isEqualTo("ORDER_LOCATION");
        assertThat(reloaded.getStatus()).isEqualTo("INACTIVE");
        assertThat(reloaded.supportedModeSet()).extracting(Enum::name).containsExactlyInAnyOrder("PICKING", "QC");
        assertThat(updated.getId()).isEqualTo(created.getId());
    }

    @Test
    void updateRejectsDuplicateCodeAndKeepsModesWhenOmitted() {
        UUID wh = UUID.randomUUID();
        GtpStation a = service.createStation(new CreateStationRequest(
                wh, "DUP-A", null, "ORDER_LOCATION", List.of("PICKING", "DECANTING"), List.of()));
        service.createStation(new CreateStationRequest(
                wh, "DUP-B", null, "ORDER_LOCATION", List.of("PICKING"), List.of()));

        // Renaming A to B's code in the same warehouse is a conflict.
        assertThatThrownBy(() -> service.updateStation(a.getId(), new UpdateStationRequest(
                "DUP-B", null, "ORDER_LOCATION", null, null)))
                .isInstanceOf(IllegalStateException.class);

        // A null supportedModes keeps the existing set.
        service.updateStation(a.getId(), new UpdateStationRequest("DUP-A2", null, "PUT_WALL", null, null));
        GtpStation reloaded = stations.findById(a.getId()).orElseThrow();
        assertThat(reloaded.getCode()).isEqualTo("DUP-A2");
        assertThat(reloaded.supportedModeSet()).extracting(Enum::name)
                .containsExactlyInAnyOrder("PICKING", "DECANTING");
    }

    @Test
    void addsUpdatesAndRemovesNodes() {
        GtpStation station = service.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-N", null, "PUT_WALL", List.of("PICKING"), List.of()));

        StationNode node = service.addNode(station.getId(),
                new AddNodeRequest("ORDER", "A1", "light-A1", null, null, 1));
        assertThat(service.nodesOf(station.getId())).hasSize(1);

        UUID loc = UUID.randomUUID();
        StationNode edited = service.updateNode(node.getId(), new UpdateNodeRequest(
                "ORDER", "A1-renamed", "light-A1b", loc, null, 3, "INACTIVE"));
        assertThat(edited.getCode()).isEqualTo("A1-renamed");
        assertThat(edited.getPutLightId()).isEqualTo("light-A1b");
        assertThat(edited.getLocationId()).isEqualTo(loc);
        assertThat(edited.getPosition()).isEqualTo(3);
        assertThat(edited.getStatus()).isEqualTo("INACTIVE");

        service.deleteNode(node.getId());
        assertThat(service.nodesOf(station.getId())).isEmpty();
    }

    @Test
    void deletingStationCascadesToNodes() {
        GtpStation station = service.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-DEL", null, "ORDER_LOCATION", List.of("PICKING"), List.of()));
        StationNode stock = service.addNode(station.getId(),
                new AddNodeRequest("STOCK", "S1", null, null, null, 0));

        service.deleteStation(station.getId());

        assertThat(stations.findById(station.getId())).isEmpty();
        assertThat(nodes.findById(stock.getId())).isEmpty();
        assertThatThrownBy(() -> service.requireStation(station.getId()))
                .isInstanceOf(NotFoundException.class);
    }
}
