package org.openwcs.counting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.counting.client.InventoryClient;
import org.openwcs.counting.client.TxLogClient;
import org.openwcs.counting.domain.CountLine;
import org.openwcs.counting.domain.CountSchedule;
import org.openwcs.counting.domain.CountTask;
import org.openwcs.counting.repo.CountLineRepository;
import org.openwcs.counting.repo.CountScheduleRepository;
import org.openwcs.counting.repo.CountTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the counting context against PostgreSQL 16 — running Flyway V1 then Hibernate
 * {@code ddl-auto=validate}, so a clean start proves every entity mapping matches the migrated
 * schema. Round-trips the three aggregates (defaults, unique keys, audit columns).
 */
@SpringBootTest
@Testcontainers
class CountingContextTest {

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

    @Autowired
    CountTaskRepository tasks;

    @Autowired
    CountLineRepository lines;

    @Autowired
    CountScheduleRepository schedules;

    @Test
    void roundTripsAggregates() {
        UUID wh = UUID.randomUUID();

        CountTask task = new CountTask();
        task.setWarehouseId(wh);
        task.setScopeType("LOCATION");
        task.setScopeRef(UUID.randomUUID());
        task.setCountType("BLIND");
        CountTask savedTask = tasks.save(task);
        assertThat(savedTask.getId()).isNotNull();
        assertThat(savedTask.getStatus()).isEqualTo("OPEN");
        assertThat(savedTask.getCreatedAt()).isNotNull();

        CountLine line = new CountLine();
        line.setCountTaskId(savedTask.getId());
        line.setWarehouseId(wh);
        line.setLocationId(UUID.randomUUID());
        line.setSkuId(UUID.randomUUID());
        line.setUomCode("EACH");
        line.setExpectedQty(new BigDecimal("42"));
        lines.save(line);
        assertThat(lines.findByCountTaskId(savedTask.getId())).hasSize(1);

        CountSchedule schedule = new CountSchedule();
        schedule.setWarehouseId(wh);
        schedule.setName("A class daily");
        schedule.setScopeType("ABC_CLASS");
        schedule.setAbcClass("A");
        schedule.setCadenceDays(1);
        CountSchedule savedSchedule = schedules.save(schedule);
        assertThat(savedSchedule.getStatus()).isEqualTo("ACTIVE");
        assertThat(schedules.findByWarehouseId(wh)).hasSize(1);
    }
}
