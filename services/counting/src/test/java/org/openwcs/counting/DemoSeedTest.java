package org.openwcs.counting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.counting.api.DemoSeedResult;
import org.openwcs.counting.client.FlowClient;
import org.openwcs.counting.client.GtpClient;
import org.openwcs.counting.client.InventoryClient;
import org.openwcs.counting.client.MasterDataClient;
import org.openwcs.counting.client.TxLogClient;
import org.openwcs.counting.repo.CountTaskRepository;
import org.openwcs.counting.service.DemoSeedService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The demo "Add 10 count tasks" seed builds N tasks over real demo stock cells, and is a 409
 * (IllegalStateException) when there is no stock to count.
 */
@SpringBootTest
@Testcontainers
class DemoSeedTest {

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
    DemoSeedService seed;

    @Autowired
    CountTaskRepository tasks;

    @Test
    void seedsCountTasksFromDemoStock() {
        UUID warehouse = UUID.randomUUID();
        when(inventory.listStockCells(warehouse)).thenReturn(List.of(
                new InventoryClient.StockCell(UUID.randomUUID(), UUID.randomUUID()),
                new InventoryClient.StockCell(UUID.randomUUID(), UUID.randomUUID()),
                new InventoryClient.StockCell(UUID.randomUUID(), UUID.randomUUID())));
        when(inventory.expectedOnHand(eq(warehouse), any(), any())).thenReturn(new BigDecimal("10"));

        DemoSeedResult result = seed.seed(warehouse, 10);

        assertThat(result.created()).isEqualTo(10);
        assertThat(tasks.findByWarehouseId(warehouse)).hasSize(10)
                .allSatisfy(t -> assertThat(t.getStatus()).isEqualTo("OPEN"));
    }

    @Test
    void rejectsWhenNoStock() {
        UUID warehouse = UUID.randomUUID();
        when(inventory.listStockCells(warehouse)).thenReturn(List.of());
        assertThatThrownBy(() -> seed.seed(warehouse, 10)).isInstanceOf(IllegalStateException.class);
    }
}
