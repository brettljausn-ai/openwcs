package org.openwcs.gtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.gtp.api.CreateStationRequest;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.StationQueueEntry;
import org.openwcs.gtp.service.GtpStationService;
import org.openwcs.gtp.service.StationQueueService;
import org.openwcs.gtp.service.StationQueueService.EnqueueCommand;
import org.openwcs.gtp.service.StationQueueService.QueueRejectedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The station inbound queue: ASRS/immediate HUs are QUEUED at once while conveyor HUs are IN_TRANSIT
 * with a distance-timed arrival; the per-mode in-transit caps and the deactivate (drain) switch both
 * reject new work; completing an entry marks it DONE.
 */
@SpringBootTest
@Testcontainers
class StationQueueTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    GtpStationService stations;

    @Autowired
    StationQueueService queue;

    private GtpStation station(String code, int capPicking) {
        GtpStation s = stations.createStation(new CreateStationRequest(
                UUID.randomUUID(), code, null, "ORDER_LOCATION", List.of("PICKING", "STOCK_COUNT"), List.of()));
        return stations.setCapacity(s.getId(), capPicking, 2);
    }

    private EnqueueCommand cmd(String mode, String family, Double distanceM) {
        return new EnqueueCommand(UUID.randomUUID(), "HU-1", UUID.randomUUID(), "SKU-1",
                new BigDecimal("5"), mode, family, distanceM);
    }

    @Test
    void asrsArrivesImmediatelyConveyorIsInTransit() {
        GtpStation s = station("GTP-Q1", 4);

        StationQueueEntry asrs = queue.enqueue(s.getId(), cmd("PICKING", "ASRS", null));
        assertThat(asrs.getStatus()).isEqualTo("QUEUED");

        StationQueueEntry conv = queue.enqueue(s.getId(), cmd("PICKING", "CONVEYOR", 10.0));
        assertThat(conv.getStatus()).isEqualTo("IN_TRANSIT");
        assertThat(conv.getArrivalAt()).isAfter(Instant.now()); // 10 m / 0.5 m/s = ~20s out
    }

    @Test
    void respectsPickingCap() {
        GtpStation s = station("GTP-Q2", 1);
        queue.enqueue(s.getId(), cmd("PICKING", "ASRS", null));
        assertThatThrownBy(() -> queue.enqueue(s.getId(), cmd("PICKING", "ASRS", null)))
                .isInstanceOf(QueueRejectedException.class);
        // A different mode class still has its own capacity.
        assertThat(queue.enqueue(s.getId(), cmd("STOCK_COUNT", "ASRS", null)).getStatus()).isEqualTo("QUEUED");
    }

    @Test
    void deactivatedStationTakesNoNewWorkThenCompletes() {
        GtpStation s = station("GTP-Q3", 4);
        StationQueueEntry e = queue.enqueue(s.getId(), cmd("PICKING", "ASRS", null));

        queue.setAccepting(s.getId(), false);
        assertThatThrownBy(() -> queue.enqueue(s.getId(), cmd("PICKING", "ASRS", null)))
                .isInstanceOf(QueueRejectedException.class);

        // Already-queued work can still be completed (drain).
        assertThat(queue.complete(e.getId()).getStatus()).isEqualTo("DONE");
    }
}
