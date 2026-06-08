package org.openwcs.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.orders.api.DemoSeedResult;
import org.openwcs.orders.client.AllocationClient;
import org.openwcs.orders.client.MasterDataClient;
import org.openwcs.orders.domain.OrderType;
import org.openwcs.orders.repo.OutboundOrderRepository;
import org.openwcs.orders.service.DemoSeedService;
import org.openwcs.orders.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The demo "Add 10 orders" seed builds N orders of a direction from the seeded demo SKUs, and is
 * a 409 (IllegalStateException) when the demo catalog is absent.
 */
@SpringBootTest
@Testcontainers
class DemoSeedTest {

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
    DemoSeedService seed;

    @Autowired
    OutboundOrderRepository orders;

    @Autowired
    OrderService service;

    @Test
    void seedsOutboundOrdersFromDemoSkus() {
        when(masterData.listDemoSkus()).thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        UUID warehouse = UUID.randomUUID();

        DemoSeedResult result = seed.seed(warehouse, "OUTBOUND", 10);

        assertThat(result.created()).isEqualTo(10);
        var saved = orders.findByWarehouseId(warehouse);
        assertThat(saved).hasSize(10)
                .allSatisfy(o -> assertThat(o.getOrderType()).isEqualTo(OrderType.OUTBOUND));
        // Read lines through the order view (mapped inside the service transaction) to avoid a lazy
        // collection access outside a session.
        assertThat(service.get(saved.get(0).getId()).lines()).isNotEmpty();
    }

    @Test
    void rejectsWhenDemoCatalogAbsent() {
        when(masterData.listDemoSkus()).thenReturn(List.of());
        assertThatThrownBy(() -> seed.seed(UUID.randomUUID(), "INBOUND", 10))
                .isInstanceOf(IllegalStateException.class);
    }
}
