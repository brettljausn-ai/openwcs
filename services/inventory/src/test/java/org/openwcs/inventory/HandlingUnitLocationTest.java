package org.openwcs.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.inventory.api.HandlingUnitController;
import org.openwcs.inventory.api.LocationUpdateRequest;
import org.openwcs.inventory.client.MasterDataClient;
import org.openwcs.inventory.client.MasterDataUnavailableException;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.openwcs.inventory.service.HandlingUnitNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * HU location booking endpoint ({@code PUT /api/inventory/handling-units/{id}/location}) against a
 * real PostgreSQL 16: HUs are ALWAYS booked to a real location. A booking with a location moves the
 * HU and the stock riding in it; a booking with {@code locationId = null} (the caller does not know
 * the position) books HU + stock to the warehouse's UNKNOWN location resolved via master-data; an
 * unreachable master-data rejects the booking (503); an unknown HU is a 404.
 */
@SpringBootTest
@Testcontainers
class HandlingUnitLocationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    HandlingUnitController controller;

    @Autowired
    HandlingUnitRepository handlingUnits;

    @Autowired
    StockRepository stock;

    @MockBean
    MasterDataClient masterData;

    private HandlingUnit hu(String code, UUID warehouseId, UUID locationId) {
        HandlingUnit hu = new HandlingUnit();
        hu.setWarehouseId(warehouseId);
        hu.setCode(code);
        hu.setLocationId(locationId);
        return handlingUnits.save(hu);
    }

    private Stock stockInHu(UUID warehouseId, UUID huId, UUID locationId, String qty) {
        Stock row = new Stock();
        row.setWarehouseId(warehouseId);
        row.setSkuId(UUID.randomUUID());
        row.setLocationId(locationId);
        row.setHuId(huId);
        row.setStatus("AVAILABLE");
        row.setQty(new BigDecimal(qty));
        return stock.save(row);
    }

    @Test
    void booksTheHuAndItsStockIntoALocation() {
        UUID warehouse = UUID.randomUUID();
        UUID oldSlot = UUID.randomUUID();
        HandlingUnit tote = hu("HU-LOC-1", warehouse, oldSlot);
        Stock riding = stockInHu(warehouse, tote.getHuId(), oldSlot, "5");
        UUID slot = UUID.randomUUID();

        HandlingUnit updated = controller.updateLocation(tote.getHuId(), new LocationUpdateRequest(slot));

        assertThat(updated.getLocationId()).isEqualTo(slot);
        assertThat(handlingUnits.findById(tote.getHuId()).orElseThrow().getLocationId()).isEqualTo(slot);
        // The stock rides in the tote: its rows must follow the booking.
        assertThat(stock.findById(riding.getId()).orElseThrow().getLocationId()).isEqualTo(slot);
    }

    @Test
    void nullBookingGoesToTheUnknownLocationInsteadOfNull() {
        UUID warehouse = UUID.randomUUID();
        UUID unknownLocation = UUID.randomUUID();
        when(masterData.unknownLocationId(warehouse)).thenReturn(unknownLocation);
        UUID oldSlot = UUID.randomUUID();
        HandlingUnit tote = hu("HU-LOC-2", warehouse, oldSlot);
        Stock riding = stockInHu(warehouse, tote.getHuId(), oldSlot, "10");

        HandlingUnit updated = controller.updateLocation(tote.getHuId(), new LocationUpdateRequest(null));

        // HUs are ALWAYS booked to a real location: null means "position unknown" = UNKNOWN.
        assertThat(updated.getLocationId()).isEqualTo(unknownLocation);
        assertThat(handlingUnits.findById(tote.getHuId()).orElseThrow().getLocationId())
                .isEqualTo(unknownLocation);
        // The stock rows follow too (location_id stays NOT NULL; this used to 500).
        assertThat(stock.findById(riding.getId()).orElseThrow().getLocationId()).isEqualTo(unknownLocation);
        // Only the location changed: code/warehouse are untouched by the focused endpoint.
        assertThat(updated.getCode()).isEqualTo("HU-LOC-2");
        assertThat(updated.getWarehouseId()).isEqualTo(warehouse);
    }

    @Test
    void nullBookingIsRejectedWhenMasterDataIsUnreachable() {
        when(masterData.unknownLocationId(any()))
                .thenThrow(new MasterDataUnavailableException("master-data down", null));
        UUID oldSlot = UUID.randomUUID();
        HandlingUnit tote = hu("HU-LOC-3", UUID.randomUUID(), oldSlot);

        assertThatThrownBy(() -> controller.updateLocation(tote.getHuId(), new LocationUpdateRequest(null)))
                .isInstanceOf(MasterDataUnavailableException.class);

        // Best-effort booking: the HU keeps its last known location (mapped to 503 for the caller).
        assertThat(handlingUnits.findById(tote.getHuId()).orElseThrow().getLocationId()).isEqualTo(oldSlot);
    }

    @Test
    void unknownHandlingUnitIsNotFound() {
        assertThatThrownBy(() -> controller.updateLocation(UUID.randomUUID(), new LocationUpdateRequest(null)))
                .isInstanceOf(HandlingUnitNotFoundException.class);
    }
}
