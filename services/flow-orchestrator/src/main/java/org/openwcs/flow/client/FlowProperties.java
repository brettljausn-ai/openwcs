package org.openwcs.flow.client;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Binds {@code openwcs.flow.*} — notably the device-adapter base URL per equipment family. */
@Component
@ConfigurationProperties(prefix = "openwcs.flow")
public class FlowProperties {

    /** family (CONVEYOR/ASRS/AMR/AUTOSTORE) → adapter base URL. */
    private Map<String, String> adapters = new HashMap<>();

    /**
     * Base URL of the single equipment-emulator. When hardware-emulator mode is ON, device tasks for
     * every family are dispatched here instead of to the per-family {@link #adapters}.
     */
    private String emulatorUrl = "http://localhost:9097";

    /**
     * This service's own base URL, handed to the device (in the {@code /tasks} body as a callback URL)
     * so an asynchronous adapter/emulator can POST the result back to
     * {@code /api/flow/device-tasks/{id}/result}.
     */
    private String selfBaseUrl = "http://localhost:8085";

    /** Base URL of the gtp service (projecting topology STOCK/ORDER interactions into station nodes). */
    private String gtpBaseUrl = "http://localhost:8094";

    /** Base URL of the inventory service (booking HU locations through the transport lifecycle). */
    private String inventoryBaseUrl = "http://localhost:8082";

    /** Base URL of the slotting service (relocation plans for multi-deep channel dig-outs, ADR-0009;
     *  put-away decisions for the return-to-storage leg: only slotting slots a tote). */
    private String slottingBaseUrl = "http://localhost:8093";

    /** Base URL of the master-data service (emulator flag; operational locations for conveyors /
     *  workplaces so HU bookings always name the tote's current physical place). */
    private String masterDataBaseUrl = "http://localhost:8081";

    public Map<String, String> getAdapters() {
        return adapters;
    }

    public void setAdapters(Map<String, String> adapters) {
        this.adapters = adapters;
    }

    public String getEmulatorUrl() {
        return emulatorUrl;
    }

    public void setEmulatorUrl(String emulatorUrl) {
        this.emulatorUrl = emulatorUrl;
    }

    public String getSelfBaseUrl() {
        return selfBaseUrl;
    }

    public void setSelfBaseUrl(String selfBaseUrl) {
        this.selfBaseUrl = selfBaseUrl;
    }

    public String getGtpBaseUrl() {
        return gtpBaseUrl;
    }

    public void setGtpBaseUrl(String gtpBaseUrl) {
        this.gtpBaseUrl = gtpBaseUrl;
    }

    public String getInventoryBaseUrl() {
        return inventoryBaseUrl;
    }

    public void setInventoryBaseUrl(String inventoryBaseUrl) {
        this.inventoryBaseUrl = inventoryBaseUrl;
    }

    public String getSlottingBaseUrl() {
        return slottingBaseUrl;
    }

    public void setSlottingBaseUrl(String slottingBaseUrl) {
        this.slottingBaseUrl = slottingBaseUrl;
    }

    public String getMasterDataBaseUrl() {
        return masterDataBaseUrl;
    }

    public void setMasterDataBaseUrl(String masterDataBaseUrl) {
        this.masterDataBaseUrl = masterDataBaseUrl;
    }
}
