package org.openwcs.notification.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads master-data's demo flag (DEMO_MODE_ENABLED) so the demo dashboard seeder can gate itself:
 * seeding is a no-op-with-error unless demo mode is on. Best-effort — any error means "off".
 */
@Component
public class DemoModeClient {

    private final RestClient masterData;

    public DemoModeClient(RestClient.Builder builder,
            @Value("${openwcs.notification.master-data-base-url:http://localhost:8081}") String masterDataUrl) {
        this.masterData = builder.clone().baseUrl(masterDataUrl).build();
    }

    /** Whether master-data demo mode is currently ON; false on any error. */
    public boolean demoEnabled() {
        try {
            DemoStatus s = masterData.get().uri("/api/master-data/demo").retrieve().body(DemoStatus.class);
            return s != null && s.enabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Subset of the master-data demo-status response. */
    private record DemoStatus(boolean enabled) {
    }
}
