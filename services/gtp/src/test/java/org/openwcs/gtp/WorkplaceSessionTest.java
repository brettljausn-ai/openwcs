package org.openwcs.gtp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.gtp.api.CreateStationRequest;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.WorkplaceSession;
import org.openwcs.gtp.repo.WorkplaceSessionRepository;
import org.openwcs.gtp.service.GtpStationService;
import org.openwcs.gtp.service.WorkplaceSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Single-active-session-per-workplace against PostgreSQL 16: claiming supersedes the prior session
 * (so its heartbeat reports the takeover), only one session is ACTIVE at a time, and release closes
 * a session cleanly. Exercises the DB partial-unique-index safety net too.
 */
@SpringBootTest
@Testcontainers
class WorkplaceSessionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    GtpStationService stationService;

    @Autowired
    WorkplaceSessionService sessions;

    @Autowired
    WorkplaceSessionRepository sessionRepo;

    @Test
    void claimSupersedesPreviousAndKeepsOnlyOneActive() {
        GtpStation station = newStation();

        WorkplaceSession first = sessions.claim(station.getId(), "alice");
        assertThat(first.getStatus()).isEqualTo(WorkplaceSession.ACTIVE);

        // First operator's heartbeat is fine while it owns the workplace.
        assertThat(sessions.heartbeat(station.getId(), first.getId()).isActive()).isTrue();

        // A second operator opens the SAME workplace elsewhere -> supersedes the first.
        WorkplaceSession second = sessions.claim(station.getId(), "bob");
        assertThat(second.getStatus()).isEqualTo(WorkplaceSession.ACTIVE);
        assertThat(second.getId()).isNotEqualTo(first.getId());

        // First operator's next heartbeat now reports the takeover.
        WorkplaceSession refreshedFirst = sessions.heartbeat(station.getId(), first.getId());
        assertThat(refreshedFirst.isActive()).isFalse();
        assertThat(refreshedFirst.getStatus()).isEqualTo(WorkplaceSession.SUPERSEDED);
        assertThat(refreshedFirst.getSupersededReason())
                .isEqualTo(WorkplaceSessionService.REASON_SUPERSEDED);

        // Exactly one ACTIVE session for the workplace.
        assertThat(sessionRepo.findByStationIdAndStatus(station.getId(), WorkplaceSession.ACTIVE))
                .isPresent()
                .get()
                .extracting(WorkplaceSession::getId)
                .isEqualTo(second.getId());
        assertThat(sessions.activeStationIds(List.of(station.getId()))).containsExactly(station.getId());
    }

    @Test
    void releaseClosesTheSessionAndFreesTheWorkplace() {
        GtpStation station = newStation();
        WorkplaceSession session = sessions.claim(station.getId(), "alice");

        WorkplaceSession released = sessions.release(station.getId(), session.getId());
        assertThat(released.getStatus()).isEqualTo(WorkplaceSession.RELEASED);
        assertThat(released.getSupersededReason()).isEqualTo(WorkplaceSessionService.REASON_RELEASED);

        // Heartbeat on a released session reports inactive; workplace is no longer in use.
        assertThat(sessions.heartbeat(station.getId(), session.getId()).isActive()).isFalse();
        assertThat(sessions.activeStationIds(List.of(station.getId()))).isEmpty();

        // Re-claiming after a clean release works (no leftover ACTIVE row to collide with).
        WorkplaceSession again = sessions.claim(station.getId(), "carol");
        assertThat(again.getStatus()).isEqualTo(WorkplaceSession.ACTIVE);
    }

    private GtpStation newStation() {
        return stationService.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-" + UUID.randomUUID(), "PUT_WALL", null, List.of()));
    }
}
