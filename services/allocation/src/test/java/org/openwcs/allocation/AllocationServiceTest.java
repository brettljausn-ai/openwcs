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

    @MockBean
    org.openwcs.allocation.client.HostLabelClient hostLabel;

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
                List.of(new AllocateOrderRequest.Line(1, sku, new BigDecimal("5"))), null, null);

        AllocationView allocated = service.allocate(request);
        assertThat(allocated.status()).isEqualTo("FULFILLABLE");
        assertThat(allocated.lines().get(0).picks().get(0).reservationId()).isEqualTo(reservation);

        AllocationView cancelled = service.cancel("ORD-CANCEL");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.lines().get(0).picks().get(0).reservationId()).isNull();
        verify(inventory).release(reservation);
    }

    @Test
    void oversizedSkuParksOrderInCubingFailedAndReleasesStock() {
        UUID warehouse = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();

        when(masterData.fulfillmentConfig(any())).thenReturn(
                new MasterDataClient.FulfillmentConfig(List.of("EACH"), "APP", null, false, 1, 12, null));
        when(masterData.pickLocationIds(any())).thenReturn(List.of(location));
        // Base UoM is 100×100×100 mm = 1,000,000 mm³ per unit.
        when(masterData.skuUoms(any())).thenReturn(List.of(new MasterDataClient.UomDef(
                UUID.randomUUID(), "EACH", null, null,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), BigDecimal.valueOf(100), null, true)));
        // Only a tiny 10×10×10 mm = 1,000 mm³ carton exists — the SKU cannot fit.
        when(masterData.shippers(any())).thenReturn(List.of(new MasterDataClient.ShipperDef(
                UUID.randomUUID(), "SMALL", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ONE, null, "ACTIVE")));
        when(inventory.availableAtLocation(any(), any(), any())).thenReturn(new BigDecimal("10"));
        when(inventory.reserve(any(), any(), any(), any(), any())).thenReturn(reservation);

        AllocateOrderRequest request = new AllocateOrderRequest(
                "ORD-BIG", warehouse, null,
                List.of(new AllocateOrderRequest.Line(1, sku, BigDecimal.ONE)), null, null);

        AllocationView view = service.allocate(request);

        assertThat(view.status()).isEqualTo("CUBING_FAILED");
        assertThat(view.statusDetail()).contains(sku.toString()).contains("does not fit");
        assertThat(view.shippers()).isEmpty();
        // Nothing is held for an order that can't ship; the reservation is released.
        assertThat(view.lines().get(0).picks().get(0).reservationId()).isNull();
        verify(inventory).release(reservation);
    }

    @Test
    void cubedShippersGetDispatchLabelsWithHostBarcodePerShipper() {
        UUID warehouse = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID location = UUID.randomUUID();

        when(masterData.fulfillmentConfig(any())).thenReturn(
                new MasterDataClient.FulfillmentConfig(List.of("EACH"), "APP", null, false, 1, 12, null));
        when(masterData.pickLocationIds(any())).thenReturn(List.of(location));
        // Base UoM 600 g, volume unconstrained.
        when(masterData.skuUoms(any())).thenReturn(List.of(new MasterDataClient.UomDef(
                UUID.randomUUID(), "EACH", null, null, null, null, null, BigDecimal.valueOf(600), true)));
        // One tote, net weight cap 1000 g -> 2 units (1200 g) need 2 cartons.
        when(masterData.shippers(any())).thenReturn(List.of(new MasterDataClient.ShipperDef(
                UUID.randomUUID(), "TOTE", null, null, null,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.valueOf(1000), "ACTIVE")));
        when(inventory.availableAtLocation(any(), any(), any())).thenReturn(new BigDecimal("10"));
        when(inventory.reserve(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        when(hostLabel.requestBarcode(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> "BC-" + inv.getArgument(4));

        var dispatch = new AllocateOrderRequest.Dispatch(
                new AllocateOrderRequest.ShipTo("Acme Ltd", "1 High St", null, "London", null, "EC1A 1AA", "GB", null, null),
                "EXPRESS", "CENTRAL_LONDON", "SHIP-4X6");
        AllocateOrderRequest request = new AllocateOrderRequest(
                "ORD-LBL", warehouse, null,
                List.of(new AllocateOrderRequest.Line(1, sku, new BigDecimal("2"))), null, dispatch);

        AllocationView view = service.allocate(request);

        assertThat(view.status()).isEqualTo("FULFILLABLE");
        assertThat(view.shippers()).hasSize(2);
        // Every carton has a dispatch label with the resolved template, shared fields, and its
        // own host-assigned barcode.
        assertThat(view.shippers()).allSatisfy(s -> {
            assertThat(s.dispatchLabel()).isNotNull();
            assertThat(s.dispatchLabel().templateCode()).isEqualTo("SHIP-4X6");
            assertThat(s.dispatchLabel().fields()).containsEntry("service", "EXPRESS")
                    .containsEntry("route", "CENTRAL_LONDON").containsEntry("shipToName", "Acme Ltd");
            assertThat(s.dispatchLabel().fields().get("addressBlock")).contains("London");
            assertThat(s.dispatchLabel().barcode()).isEqualTo("BC-" + s.seqNo());
        });
        assertThat(view.shippers().get(0).dispatchLabel().fields()).containsEntry("carton", "1/2");
        // A barcode was requested from the host once per shipper.
        verify(hostLabel, org.mockito.Mockito.times(2))
                .requestBarcode(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
