package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openwcs.flow.api.TwinTelemetryDtos.AmrFleet;
import org.openwcs.flow.api.TwinTelemetryDtos.AsrsCranes;
import org.openwcs.flow.api.TwinTelemetryDtos.Autostore;
import org.openwcs.flow.client.EmulatorTwinClient;
import org.openwcs.flow.client.EmulatorTwinClient.AmrTelemetry;
import org.openwcs.flow.client.EmulatorTwinClient.AutostoreTelemetry;
import org.openwcs.flow.client.EmulatorTwinClient.CraneTelemetry;
import org.openwcs.flow.client.EmulatorTwinClient.PortTelemetry;
import org.openwcs.flow.domain.PlacedEquipment;
import org.openwcs.flow.repo.PlacedEquipmentRepository;
import org.openwcs.flow.service.TwinTelemetryService;
import org.springframework.web.client.RestClientException;

/**
 * The AMR/AutoStore twin telemetry read model: maps the equipment-emulator's robot/grid telemetry to
 * world coordinates and degrades to an empty fleet / zeroed grid when the emulator client throws.
 */
@ExtendWith(MockitoExtension.class)
class TwinTelemetryServiceTest {

    @Mock
    EmulatorTwinClient emulator;

    @Mock
    PlacedEquipmentRepository equipment;

    TwinTelemetryService service() {
        return new TwinTelemetryService(emulator, equipment);
    }

    @Test
    void amrFleetMapsEmulatorTelemetryToWorldCoords() {
        UUID wh = UUID.randomUUID();
        when(equipment.findByWarehouseId(wh)).thenReturn(List.of());
        when(emulator.amrFleet()).thenReturn(List.of(
                new AmrTelemetry("AMR-1", 3.5, -2.0, "MOVING", "HU-9"),
                new AmrTelemetry("AMR-2", 0.0, 0.0, "IDLE", null)));

        AmrFleet fleet = service().amrFleet(wh);

        assertThat(fleet.serverTimeMs()).isGreaterThan(0);
        assertThat(fleet.amrs()).hasSize(2);
        assertThat(fleet.amrs().get(0).amrId()).isEqualTo("AMR-1");
        assertThat(fleet.amrs().get(0).x()).isEqualTo(3.5);
        assertThat(fleet.amrs().get(0).z()).isEqualTo(-2.0);
        assertThat(fleet.amrs().get(0).status()).isEqualTo("MOVING");
        assertThat(fleet.amrs().get(0).huCode()).isEqualTo("HU-9");
        assertThat(fleet.amrs().get(1).huCode()).isNull();
    }

    @Test
    void amrFleetFallsBackToPlacedEquipmentWhenEmulatorOmitsCoords() {
        UUID wh = UUID.randomUUID();
        PlacedEquipment placed = new PlacedEquipment();
        placed.setCode("AMR-7");
        placed.setPosXM(new BigDecimal("12.0"));
        placed.setPosZM(new BigDecimal("-4.0"));
        when(equipment.findByWarehouseId(wh)).thenReturn(List.of(placed));
        when(emulator.amrFleet()).thenReturn(List.of(
                new AmrTelemetry("AMR-7", null, null, "IDLE", null)));

        AmrFleet fleet = service().amrFleet(wh);

        assertThat(fleet.amrs()).hasSize(1);
        assertThat(fleet.amrs().get(0).x()).isEqualTo(12.0);
        assertThat(fleet.amrs().get(0).z()).isEqualTo(-4.0);
    }

    @Test
    void amrFleetDegradesToEmptyWhenEmulatorThrows() {
        UUID wh = UUID.randomUUID();
        when(emulator.amrFleet()).thenThrow(new RestClientException("emulator off"));

        AmrFleet fleet = service().amrFleet(wh);

        assertThat(fleet.serverTimeMs()).isGreaterThan(0);
        assertThat(fleet.amrs()).isEmpty();
    }

    @Test
    void autostoreMapsGridAndPorts() {
        UUID wh = UUID.randomUUID();
        when(equipment.findByWarehouseId(wh)).thenReturn(List.of());
        when(emulator.autostoreGrid()).thenReturn(new AutostoreTelemetry(20, 15, 180, 300,
                List.of(new PortTelemetry("PORT-1", 1.0, 2.0, true, "HU-3"),
                        new PortTelemetry("PORT-2", 3.0, 4.0, false, null))));

        Autostore as = service().autostore(wh);

        assertThat(as.serverTimeMs()).isGreaterThan(0);
        assertThat(as.grid().columns()).isEqualTo(20);
        assertThat(as.grid().rows()).isEqualTo(15);
        assertThat(as.grid().occupiedBins()).isEqualTo(180);
        assertThat(as.grid().totalBins()).isEqualTo(300);
        assertThat(as.ports()).hasSize(2);
        assertThat(as.ports().get(0).portId()).isEqualTo("PORT-1");
        assertThat(as.ports().get(0).x()).isEqualTo(1.0);
        assertThat(as.ports().get(0).busy()).isTrue();
        assertThat(as.ports().get(0).huCode()).isEqualTo("HU-3");
        assertThat(as.ports().get(1).busy()).isFalse();
    }

    @Test
    void autostorePortFallsBackToPlacedEquipmentCoords() {
        UUID wh = UUID.randomUUID();
        PlacedEquipment placed = new PlacedEquipment();
        placed.setCode("PORT-9");
        placed.setPosXM(new BigDecimal("5.5"));
        placed.setPosZM(new BigDecimal("6.5"));
        when(equipment.findByWarehouseId(wh)).thenReturn(List.of(placed));
        when(emulator.autostoreGrid()).thenReturn(new AutostoreTelemetry(10, 10, 0, 100,
                List.of(new PortTelemetry("PORT-9", null, null, false, null))));

        Autostore as = service().autostore(wh);

        assertThat(as.ports().get(0).x()).isEqualTo(5.5);
        assertThat(as.ports().get(0).z()).isEqualTo(6.5);
    }

    @Test
    void autostoreDegradesToEmptyWhenEmulatorThrows() {
        UUID wh = UUID.randomUUID();
        when(emulator.autostoreGrid()).thenThrow(new RestClientException("emulator off"));

        Autostore as = service().autostore(wh);

        assertThat(as.serverTimeMs()).isGreaterThan(0);
        assertThat(as.grid().totalBins()).isZero();
        assertThat(as.ports()).isEmpty();
    }

    @Test
    void asrsCranesMapTelemetryToWorldCoords() {
        UUID wh = UUID.randomUUID();
        when(equipment.findByWarehouseId(wh)).thenReturn(List.of());
        when(emulator.asrsCranes()).thenReturn(List.of(
                new CraneTelemetry("CRANE-1", 0, 4.0, 2.5, -1.0, true, "MOVING", "HU-5"),
                new CraneTelemetry("CRANE-2", 1, 8.0, 0.0, 3.0, false, "IDLE", null)));

        AsrsCranes cranes = service().asrsCranes(wh);

        assertThat(cranes.serverTimeMs()).isGreaterThan(0);
        assertThat(cranes.cranes()).hasSize(2);
        assertThat(cranes.cranes().get(0).craneId()).isEqualTo("CRANE-1");
        assertThat(cranes.cranes().get(0).x()).isEqualTo(4.0);
        assertThat(cranes.cranes().get(0).y()).isEqualTo(2.5); // lift height
        assertThat(cranes.cranes().get(0).z()).isEqualTo(-1.0);
        assertThat(cranes.cranes().get(0).status()).isEqualTo("MOVING");
        assertThat(cranes.cranes().get(0).huCode()).isEqualTo("HU-5");
        assertThat(cranes.cranes().get(1).status()).isEqualTo("IDLE");
        assertThat(cranes.cranes().get(1).huCode()).isNull();
    }

    @Test
    void asrsCraneFallsBackToPlacedEquipmentWhenEmulatorOmitsWorldCoords() {
        UUID wh = UUID.randomUUID();
        PlacedEquipment placed = new PlacedEquipment();
        placed.setCode("CRANE-9");
        placed.setPosXM(new BigDecimal("20.0"));
        placed.setPosYM(new BigDecimal("1.5"));
        placed.setPosZM(new BigDecimal("-7.0"));
        when(equipment.findByWarehouseId(wh)).thenReturn(List.of(placed));
        when(emulator.asrsCranes()).thenReturn(List.of(
                new CraneTelemetry("CRANE-9", 2, null, null, null, true, null, null)));

        AsrsCranes cranes = service().asrsCranes(wh);

        assertThat(cranes.cranes()).hasSize(1);
        assertThat(cranes.cranes().get(0).x()).isEqualTo(20.0);
        assertThat(cranes.cranes().get(0).y()).isEqualTo(1.5);
        assertThat(cranes.cranes().get(0).z()).isEqualTo(-7.0);
        // No explicit status but busy=true ⇒ derived MOVING.
        assertThat(cranes.cranes().get(0).status()).isEqualTo("MOVING");
    }

    @Test
    void asrsCranesDegradeToEmptyWhenEmulatorThrows() {
        UUID wh = UUID.randomUUID();
        when(emulator.asrsCranes()).thenThrow(new RestClientException("emulator off"));

        AsrsCranes cranes = service().asrsCranes(wh);

        assertThat(cranes.serverTimeMs()).isGreaterThan(0);
        assertThat(cranes.cranes()).isEmpty();
    }
}
