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

    /** Base URL of the gtp service (projecting topology STOCK/ORDER interactions into station nodes). */
    private String gtpBaseUrl = "http://localhost:8094";

    public Map<String, String> getAdapters() {
        return adapters;
    }

    public void setAdapters(Map<String, String> adapters) {
        this.adapters = adapters;
    }

    public String getGtpBaseUrl() {
        return gtpBaseUrl;
    }

    public void setGtpBaseUrl(String gtpBaseUrl) {
        this.gtpBaseUrl = gtpBaseUrl;
    }
}
