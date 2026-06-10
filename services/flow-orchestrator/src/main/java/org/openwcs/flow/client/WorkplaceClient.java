package org.openwcs.flow.client;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads the destination workplace's in-transit caps (ADR-0007 §4.1). For 3c-1 the workplace is a GTP
 * station, so the caps come from {@code GET /api/gtp/stations/{id}} (which exposes
 * {@code maxInTransitPicking} / {@code maxInTransitOther}). Best-effort and short-timeouted: if gtp
 * is slow or unreachable the cap lookup falls back to a default, so induction never stalls.
 * (A station-config copy into flow is 3c-2 — not built here.)
 */
@Component
public class WorkplaceClient {

    private static final Logger log = LoggerFactory.getLogger(WorkplaceClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** Default cap when the workplace's config can't be fetched. */
    public static final int DEFAULT_CAP = 2;

    private final RestClient http;

    public WorkplaceClient(RestClient.Builder builder, FlowProperties properties) {
        var factory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(TIMEOUT).withReadTimeout(TIMEOUT));
        this.http = builder.baseUrl(properties.getGtpBaseUrl()).requestFactory(factory).build();
    }

    /** The {IN_TRANSIT, QUEUED} caps for a workplace; {@link #DEFAULT_CAP} for both when unavailable. */
    public Caps caps(UUID workplaceId) {
        try {
            StationCaps s = http.get()
                    .uri("/api/gtp/stations/{id}", workplaceId)
                    .retrieve()
                    .body(StationCaps.class);
            if (s == null) {
                return new Caps(DEFAULT_CAP, DEFAULT_CAP);
            }
            return new Caps(s.maxInTransitPicking(), s.maxInTransitOther());
        } catch (RestClientException e) {
            log.debug("Workplace {} cap lookup failed ({}); defaulting to {}", workplaceId, e.toString(), DEFAULT_CAP);
            return new Caps(DEFAULT_CAP, DEFAULT_CAP);
        }
    }

    /** Per-mode-class in-transit caps for a workplace. */
    public record Caps(int picking, int other) {
    }

    /** Slice of the gtp station view we need; extra fields in the response are ignored. */
    private record StationCaps(int maxInTransitPicking, int maxInTransitOther) {
    }
}
