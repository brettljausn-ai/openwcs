package org.openwcs.flow.client;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads live device telemetry from the equipment-emulator for the hardware twin (execution-layer
 * item 3): {@code GET {emulatorUrl}/amr/fleet} (AMR robot positions/status) and
 * {@code GET {emulatorUrl}/autostore/grid} (AutoStore grid fill + port activity).
 *
 * <p>Best-effort by contract: the twin endpoints degrade to an empty view when the emulator is off
 * or unreachable, so this client surfaces failures as exceptions for the caller to catch rather than
 * swallowing them here. Bounded by short timeouts so a slow emulator can't stall a twin poll.
 */
@Component
public class EmulatorTwinClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RestClient http;

    public EmulatorTwinClient(RestClient.Builder builder, FlowProperties properties) {
        var factory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(TIMEOUT).withReadTimeout(TIMEOUT));
        this.http = builder.baseUrl(properties.getEmulatorUrl()).requestFactory(factory).build();
    }

    /** AMR robot telemetry: one entry per active robot, world XZ metres + status + carried HU. */
    public List<AmrTelemetry> amrFleet() {
        List<AmrTelemetry> body = http.get()
                .uri("/amr/fleet")
                .retrieve()
                .body(new ParameterizedTypeReference<List<AmrTelemetry>>() {
                });
        return body == null ? List.of() : body;
    }

    /** AutoStore telemetry: grid fill + per-port activity. */
    public AutostoreTelemetry autostoreGrid() {
        return http.get()
                .uri("/autostore/grid")
                .retrieve()
                .body(AutostoreTelemetry.class);
    }

    /** ASRS crane telemetry: one entry per crane, aisle + world XYZ metres + busy/status + carried HU. */
    public List<CraneTelemetry> asrsCranes() {
        List<CraneTelemetry> body = http.get()
                .uri("/asrs/cranes")
                .retrieve()
                .body(new ParameterizedTypeReference<List<CraneTelemetry>>() {
                });
        return body == null ? List.of() : body;
    }

    /** One AMR robot as the emulator reports it. */
    public record AmrTelemetry(String amrId, Double x, Double z, String status, String huCode) {
    }

    /** The AutoStore grid + ports as the emulator reports them. */
    public record AutostoreTelemetry(Integer columns, Integer rows, Integer occupiedBins,
                                     Integer totalBins, List<PortTelemetry> ports) {
    }

    /** One AutoStore port as the emulator reports it. */
    public record PortTelemetry(String portId, Double x, Double z, Boolean busy, String huCode) {
    }

    /**
     * One ASRS crane as the emulator reports it: {@code aisle} index, world {@code x}/{@code z} metres
     * and {@code y} lift height metres (any may be null when the emulator omits world coords),
     * {@code busy} flag and free-form {@code status}, and the carried HU code (null when empty).
     */
    public record CraneTelemetry(String craneId, Integer aisle, Double x, Double y, Double z,
                                 Boolean busy, String status, String huCode) {
    }
}
