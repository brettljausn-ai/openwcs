package org.openwcs.counting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.counting.client.FlowClient;
import org.openwcs.counting.client.GtpClient;
import org.openwcs.counting.client.InventoryClient;
import org.openwcs.counting.client.MasterDataClient;
import org.openwcs.counting.client.TxLogClient;
import org.openwcs.counting.domain.CountSchedule;
import org.openwcs.counting.domain.CountTask;
import org.openwcs.counting.repo.CountScheduleRepository;
import org.openwcs.counting.service.CountScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the ABC-cadence generator emits a CountTask for every active schedule that has come due
 * (and only those), then advances each schedule's next-due time by its cadence. A short cadence (A)
 * and a long cadence (C) are both seeded due; a not-yet-due schedule must not emit.
 */
@SpringBootTest
@Testcontainers
class CountScheduleTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean
    InventoryClient inventory;

    @MockBean
    TxLogClient txlog;

    @MockBean
    MasterDataClient masterData;

    @MockBean
    GtpClient gtp;

    @MockBean
    FlowClient flow;

    @Autowired
    CountScheduleService schedules;

    @Autowired
    CountScheduleRepository scheduleRepo;

    private CountSchedule schedule(UUID wh, String name, String abc, int cadenceDays, Instant nextDue) {
        CountSchedule s = new CountSchedule();
        s.setWarehouseId(wh);
        s.setName(name);
        s.setScopeType("ABC_CLASS");
        s.setAbcClass(abc);
        s.setCountType("BLIND");
        s.setCadenceDays(cadenceDays);
        s.setTolerance(BigDecimal.ZERO);
        s.setNextDueAt(nextDue);
        return s;
    }

    @Test
    void generatesDueTasksAndAdvancesCadence() {
        UUID wh = UUID.randomUUID();
        Instant now = Instant.now();

        // A: counted often (cadence 1d), due now. C: rarely (cadence 30d), due now. B: not yet due.
        CountSchedule a = schedules.create(schedule(wh, "A class", "A", 1, now.minus(1, ChronoUnit.HOURS)));
        CountSchedule c = schedules.create(schedule(wh, "C class", "C", 30, now.minus(1, ChronoUnit.HOURS)));
        CountSchedule b = schedules.create(schedule(wh, "B class", "B", 7, now.plus(7, ChronoUnit.DAYS)));

        List<CountTask> emitted = schedules.generateDueTasks(wh, now);

        // Only the two due schedules (A, C) emit; B does not.
        assertThat(emitted).hasSize(2);
        assertThat(emitted).allSatisfy(t -> {
            assertThat(t.getOrigin()).isEqualTo("SCHEDULED");
            assertThat(t.getStatus()).isEqualTo("OPEN");
            assertThat(t.getWarehouseId()).isEqualTo(wh);
            assertThat(t.getScheduleId()).isIn(a.getId(), c.getId());
        });

        // Each emitted schedule's next-due advanced by its cadence; B untouched.
        CountSchedule aAfter = scheduleRepo.findById(a.getId()).orElseThrow();
        CountSchedule cAfter = scheduleRepo.findById(c.getId()).orElseThrow();
        CountSchedule bAfter = scheduleRepo.findById(b.getId()).orElseThrow();
        assertThat(aAfter.getNextDueAt()).isAfter(now);
        assertThat(cAfter.getNextDueAt()).isAfter(aAfter.getNextDueAt()); // 30d > 1d
        assertThat(bAfter.getLastRunAt()).isNull();

        // A second immediate sweep emits nothing (everything advanced past now).
        assertThat(schedules.generateDueTasks(wh, now)).isEmpty();
    }
}
