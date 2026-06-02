package org.openwcs.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.allocation.api.AllocateOrderRequest;
import org.openwcs.allocation.api.AllocationView;
import org.openwcs.allocation.client.InventoryClient;
import org.openwcs.allocation.client.MasterDataClient;
import org.openwcs.allocation.service.AllocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the allocation service against PostgreSQL 16 with the master-data + inventory
 * clients mocked. Verifies that allocate reserves stock and that cancel releases every
 * held reservation and marks the plan CANCELLED.
 */
@SpringBootTest
@Testcontainers
class AllocationServiceTest {

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

    @MockBean
    InventoryClient inventory;

    @Autowired
    AllocationService service;

    @Test
    void allocateThenCancelReleasesReservations() {
        UUID warehouse = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();

        when(masterData.fulfillmentConfig(any())).thenReturn(
                new MasterDataClient.FulfillmentConfig(List.of("EACH"), "APP", null, false, 1, 12, null));
        when(masterData.pickLocationIds(any())).thenReturn(List.of(location));
        when(masterData.skuUoms(any())).thenReturn(List.of(
                new MasterDataClient.UomDef(UUID.randomUUID(), "EACH", null, null, null, null, null, null, true)));
        when(masterData.shippers(any())).thenReturn(List.of());
        when(inventory.availableAtLocation(any(), any(), any())).thenReturn(new BigDecimal("10"));
        when(inventory.reserve(any(), any(), any(), any(), any())).thenReturn(reservation);

        AllocateOrderRequest request = new AllocateOrderRequest(
                "ORD-CANCEL", warehouse, null,
                List.of(new AllocateOrderRequest.Line(1, sku, new BigDecimal("5"))), null);

        AllocationView allocated = service.allocate(request);
        assertThat(allocated.status()).isEqualTo("FULFILLABLE");
        assertThat(allocated.lines().get(0).picks().get(0).reservationId()).isEqualTo(reservation);

        AllocationView cancelled = service.cancel("ORD-CANCEL");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.lines().get(0).picks().get(0).reservationId()).isNull();
        verify(inventory).release(reservation);
    }
}
