package org.openwcs.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.inventory.api.HandlingUnitController;
import org.openwcs.inventory.api.LocationUpdateRequest;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.service.HandlingUnitNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * HU location booking endpoint ({@code PUT /api/inventory/handling-units/{id}/location}) against a
 * real PostgreSQL 16: a RETRIEVE books the HU out of its slot (locationId = null), the return-leg
 * STORE books it back, and an unknown HU is a 404 (HandlingUnitNotFoundException).
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

    private HandlingUnit hu(String code, UUID locationId) {
        HandlingUnit hu = new HandlingUnit();
        hu.setWarehouseId(UUID.randomUUID());
        hu.setCode(code);
        hu.setLocationId(locationId);
        return handlingUnits.save(hu);
    }

    @Test
    void booksTheHuIntoALocation() {
        HandlingUnit tote = hu("HU-LOC-1", null);
        UUID slot = UUID.randomUUID();

        HandlingUnit updated = controller.updateLocation(tote.getHuId(), new LocationUpdateRequest(slot));

        assertThat(updated.getLocationId()).isEqualTo(slot);
        assertThat(handlingUnits.findById(tote.getHuId()).orElseThrow().getLocationId()).isEqualTo(slot);
    }

    @Test
    void clearsTheLocationWhenBookedOutOfItsSlot() {
        HandlingUnit tote = hu("HU-LOC-2", UUID.randomUUID());

        HandlingUnit updated = controller.updateLocation(tote.getHuId(), new LocationUpdateRequest(null));

        assertThat(updated.getLocationId()).isNull();
        assertThat(handlingUnits.findById(tote.getHuId()).orElseThrow().getLocationId()).isNull();
        // Only the location changed: code/warehouse are untouched by the focused endpoint.
        assertThat(updated.getCode()).isEqualTo("HU-LOC-2");
        assertThat(updated.getWarehouseId()).isEqualTo(tote.getWarehouseId());
    }

    @Test
    void unknownHandlingUnitIsNotFound() {
        assertThatThrownBy(() -> controller.updateLocation(UUID.randomUUID(), new LocationUpdateRequest(null)))
                .isInstanceOf(HandlingUnitNotFoundException.class);
    }
}
