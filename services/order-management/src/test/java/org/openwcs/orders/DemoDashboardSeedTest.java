package org.openwcs.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.orders.api.DemoDashboardSeedResult;
import org.openwcs.orders.client.AllocationClient;
import org.openwcs.orders.client.MasterDataClient;
import org.openwcs.orders.domain.OrderType;
import org.openwcs.orders.repo.OutboundOrderRepository;
import org.openwcs.orders.service.DemoDashboardSeedService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The demo DASHBOARD seed backfills a backdated spread of INBOUND + OUTBOUND orders (with shipped /
 * short / receive-error variants) when demo mode is ON, and is a 409 (IllegalStateException) when
 * the demo catalog is absent (demo mode OFF).
 */
@SpringBootTest
@Testcontainers
class DemoDashboardSeedTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("openwcs.orders.relay.enabled", () -> "false");
    }

    @MockBean
    AllocationClient allocation;

    @MockBean
    MasterDataClient masterData;

    @Autowired
    DemoDashboardSeedService seed;

    @Autowired
    OutboundOrderRepository orders;

    @Test
    void seedsBackdatedSpreadWhenDemoModeOn() {
        when(masterData.listDemoSkus()).thenReturn(List.of(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        UUID warehouse = UUID.randomUUID();

        DemoDashboardSeedResult result = seed.seed(warehouse);

        assertThat(result.orders()).isGreaterThan(50);
        assertThat(result.transactions()).isGreaterThan(0);

        var saved = orders.findByWarehouseId(warehouse);
        assertThat(saved).hasSize(result.orders());
        // Both directions are represented, and some are SHIPPED and backdated into the past.
        assertThat(saved).anyMatch(o -> o.getOrderType() == OrderType.OUTBOUND);
        assertThat(saved).anyMatch(o -> o.getOrderType() == OrderType.INBOUND);
        assertThat(saved).anyMatch(o -> o.getStatus().name().equals("SHIPPED"));
        assertThat(saved).anyMatch(o -> o.getRouteCode() != null);
        assertThat(saved).anyMatch(o -> o.getCreatedAt().isBefore(java.time.Instant.now().minusSeconds(86_400)));
    }

    @Test
    void rejectsWhenDemoModeOff() {
        when(masterData.listDemoSkus()).thenReturn(List.of());
        assertThatThrownBy(() -> seed.seed(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }
}
