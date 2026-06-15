package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.DemoDashboardSeedResult;
import org.openwcs.flow.api.ReportingDtos.AutomationSummary;
import org.openwcs.flow.client.MasterDataClient;
import org.openwcs.flow.service.FlowDashboardSeedService;
import org.openwcs.flow.service.ReportingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The flow-orchestrator demo dashboard seed bumps today's scan-quality counters and marks one
 * equipment faulted when demo mode is ON, lighting /reports/automation-summary; it is a 409
 * (IllegalStateException) when demo mode is OFF.
 */
@SpringBootTest
@Testcontainers
class DemoDashboardSeedTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean
    MasterDataClient masterData;

    @Autowired
    FlowDashboardSeedService seed;

    @Autowired
    ReportingService reporting;

    @Test
    void seedsScanStatsAndFaultWhenDemoModeOn() {
        when(masterData.demoEnabled()).thenReturn(true);
        UUID warehouse = UUID.randomUUID();

        DemoDashboardSeedResult result = seed.seed(warehouse);

        assertThat(result.scanNodes()).isGreaterThan(0);
        assertThat(result.scansBumped()).isGreaterThan(0);
        assertThat(result.faultedEquipment()).isEqualTo(1);

        AutomationSummary s = reporting.automationSummary(warehouse);
        // Some no-reads today (the elevated node), but not a runaway rate.
        assertThat(s.scanNoReadPctToday()).isGreaterThan(0.0).isLessThan(20.0);
        // One placed equipment, currently in fault, so availability dipped below 100%.
        assertThat(s.equipmentTotal()).isGreaterThanOrEqualTo(1);
        assertThat(s.equipmentInFault()).isEqualTo(1);
        assertThat(s.equipmentAvailabilityPct()).isLessThan(100.0);
    }

    @Test
    void rejectsWhenDemoModeOff() {
        when(masterData.demoEnabled()).thenReturn(false);
        assertThatThrownBy(() -> seed.seed(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }
}
