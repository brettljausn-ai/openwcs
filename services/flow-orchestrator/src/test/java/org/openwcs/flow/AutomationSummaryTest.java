package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.ReportingDtos.AutomationSummary;
import org.openwcs.flow.service.ReportingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots flow-orchestrator against PostgreSQL 16 and exercises the automation summary: today's scan
 * no-read percentage from {@code scan_stat}, and equipment availability derived from the placed
 * equipment whose most recent {@code device_task} is FAILED (currently in fault).
 */
@SpringBootTest
@Testcontainers
class AutomationSummaryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    ReportingService reporting;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void summarisesScanNoReadsAndEquipmentAvailability() {
        UUID wh = UUID.randomUUID();

        // Today's scan totals: 100 scans, 5 no-reads => 5% no-read rate.
        jdbc.update("INSERT INTO flow.scan_stat (warehouse_id, node_code, day, scans, no_reads, unknowns)"
                + " VALUES (?, 'INDUCT', CURRENT_DATE, 80, 3, 0)", wh);
        jdbc.update("INSERT INTO flow.scan_stat (warehouse_id, node_code, day, scans, no_reads, unknowns)"
                + " VALUES (?, 'DIVERT', CURRENT_DATE, 20, 2, 0)", wh);
        // A row from a prior day must NOT count toward today.
        jdbc.update("INSERT INTO flow.scan_stat (warehouse_id, node_code, day, scans, no_reads, unknowns)"
                + " VALUES (?, 'OLD', CURRENT_DATE - 1, 999, 999, 0)", wh);

        // Four placed equipments; one is currently in fault (its latest device task is FAILED).
        UUID faulted = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        UUID recovered = UUID.randomUUID();
        UUID idle = UUID.randomUUID();   // no tasks at all -> healthy
        for (UUID eq : new UUID[] {faulted, healthy, recovered, idle}) {
            jdbc.update("INSERT INTO flow.placed_equipment (warehouse_id, equipment_id, code)"
                    + " VALUES (?, ?, ?)", wh, eq, "EQ-" + eq.toString().substring(0, 4));
        }

        // faulted: latest task FAILED.
        seedTask(wh, faulted, "FAILED", 0);
        seedTask(wh, faulted, "COMPLETED", 5);    // older, ignored
        // healthy: latest task COMPLETED.
        seedTask(wh, healthy, "COMPLETED", 0);
        // recovered: an old FAILED, but the latest task COMPLETED -> healthy.
        seedTask(wh, recovered, "FAILED", 5);
        seedTask(wh, recovered, "COMPLETED", 0);

        AutomationSummary s = reporting.automationSummary(wh);

        assertThat(s.scanNoReadPctToday()).isCloseTo(5.0, within(1e-9)); // 5 of 100
        assertThat(s.equipmentTotal()).isEqualTo(4);
        assertThat(s.equipmentInFault()).isEqualTo(1);                   // only "faulted"
        assertThat(s.equipmentAvailabilityPct()).isCloseTo(75.0, within(1e-9)); // 3 of 4
    }

    @Test
    void emptyWarehouseReportsZeroNoReadsAndFullAvailability() {
        UUID wh = UUID.randomUUID();
        AutomationSummary s = reporting.automationSummary(wh);
        assertThat(s.scanNoReadPctToday()).isZero();          // no scans today
        assertThat(s.equipmentTotal()).isZero();
        assertThat(s.equipmentInFault()).isZero();
        assertThat(s.equipmentAvailabilityPct()).isEqualTo(100.0); // nothing placed -> fully available
    }

    private void seedTask(UUID wh, UUID equipmentId, String status, int minutesAgo) {
        jdbc.update("INSERT INTO flow.device_task (warehouse_id, family, equipment_id, command,"
                        + " payload, status, created_at) VALUES (?, 'ASRS', ?, 'MOVE', '{}'::jsonb, ?,"
                        + " now() - make_interval(mins => ?))",
                wh, equipmentId, status, minutesAgo);
    }
}
