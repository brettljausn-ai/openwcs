package org.openwcs.flow.client;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link EmulatorModeClient} backed by master-data's {@code GET /api/master-data/emulator}. The
 * value is cached for a short TTL so dispatch doesn't make a master-data call per device task; the
 * cache refreshes lazily on the first read after it goes stale. On any error the last known value is
 * kept (initial default OFF), mirroring the Go adapters' poller semantics.
 */
@Component
public class HttpEmulatorModeClient implements EmulatorModeClient {

    private static final Logger log = LoggerFactory.getLogger(HttpEmulatorModeClient.class);
    private static final long TTL_MILLIS = 5_000;

    private final RestClient http;

    private volatile boolean lastValue;
    private volatile long lastCheckedAt;

    public HttpEmulatorModeClient(RestClient.Builder builder,
                                  @Value("${openwcs.flow.master-data-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public boolean enabled() {
        long now = System.currentTimeMillis();
        if (now - lastCheckedAt < TTL_MILLIS) {
            return lastValue;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - lastCheckedAt < TTL_MILLIS) {
                return lastValue; // another thread just refreshed
            }
            try {
                EmulatorStatus status = http.get()
                        .uri("/api/master-data/emulator")
                        .retrieve()
                        .body(EmulatorStatus.class);
                lastValue = status != null && status.enabled();
            } catch (RuntimeException e) {
                log.warn("could not read the hardware-emulator flag from master-data; dispatch keeps "
                        + "the last known value ({}): {}", lastValue ? "on" : "off", e.toString());
            }
            lastCheckedAt = System.currentTimeMillis();
            return lastValue;
        }
    }

    /** Subset of the master-data emulator-status response. */
    private record EmulatorStatus(boolean enabled) {
    }
}
