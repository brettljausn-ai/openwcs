package org.openwcs.flow.client;

import java.util.HashMap;
import java.util.Map;
import org.openwcs.flow.domain.DeviceTask;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the equipment adapter's {@code POST /tasks} over HTTP. The target is resolved by mode:
 * when hardware-emulator mode is ON, every family goes to the single equipment-emulator; when OFF,
 * the task's family selects the real per-family adapter. Synchronous (simulator-friendly); see
 * {@link DeviceClient} for the production async contract.
 */
@Component
public class HttpDeviceClient implements DeviceClient {

    private final RestClient.Builder builder;
    private final FlowProperties properties;
    private final EmulatorModeClient emulatorMode;

    public HttpDeviceClient(RestClient.Builder builder, FlowProperties properties,
                            EmulatorModeClient emulatorMode) {
        this.builder = builder;
        this.properties = properties;
        this.emulatorMode = emulatorMode;
    }

    @Override
    public DeviceResult execute(DeviceTask task) {
        String baseUrl = resolveBaseUrl(task.getFamily(), emulatorMode.enabled());
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", task.getId());
        body.put("warehouseId", task.getWarehouseId());
        body.put("equipmentId", task.getEquipmentId());
        // The emulator multiplexes all families, so it needs the family to pick the command set;
        // real per-family adapters ignore the extra field.
        body.put("family", task.getFamily());
        body.put("command", task.getCommand());
        body.put("payload", task.getPayload());
        // Where an asynchronous device posts the terminal result back. The emulator uses this to
        // ack 202/ACCEPTED now and call back later; synchronous adapters ignore it and reply inline.
        body.put("callbackUrl", properties.getSelfBaseUrl() + "/api/flow/device-tasks/" + task.getId() + "/result");

        return builder.baseUrl(baseUrl).build()
                .post()
                .uri("/tasks")
                .body(body)
                .retrieve()
                .body(DeviceResult.class);
    }

    /**
     * Pick the adapter base URL: the equipment-emulator when emulator mode is on, otherwise the
     * real adapter for the task's family. Throws {@link NoAdapterException} when no target is
     * configured. Package-visible would suffice for production, but the unit test lives in the
     * parent {@code org.openwcs.flow} package, so this is public.
     */
    public String resolveBaseUrl(String family, boolean emulatorOn) {
        if (emulatorOn) {
            String url = properties.getEmulatorUrl();
            if (url == null || url.isBlank()) {
                throw new NoAdapterException("emulator (HARDWARE_EMULATOR_ENABLED is on)");
            }
            return url;
        }
        String url = properties.getAdapters().get(family);
        if (url == null) {
            throw new NoAdapterException(family);
        }
        return url;
    }
}
