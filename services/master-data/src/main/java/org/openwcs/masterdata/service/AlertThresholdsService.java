package org.openwcs.masterdata.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.openwcs.masterdata.domain.SystemConfiguration;
import org.openwcs.masterdata.repo.SystemConfigurationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard alert thresholds: the single store for every threshold the dashboards/alerting epic
 * watches (scan no-read rate, daily receive errors, ASRS utilisation, blocked order lines, putaway
 * backlog age, replenishment oldest age). Persisted as ONE JSON value in {@code system_configuration}
 * under {@link #ALERT_THRESHOLDS_KEY} (same key/value pattern as the stock rules and demo-mode
 * switches); reads are open, writes are ADMIN-only (gated in the controller). Reads return the baked
 * defaults merged with any stored overrides, so new threshold keys appear automatically.
 */
@Service
public class AlertThresholdsService {

    static final String ALERT_THRESHOLDS_KEY = "DASHBOARD_ALERT_THRESHOLDS";

    /** Sensible defaults shipped with the service; stored overrides are merged on top. */
    static final Map<String, Object> DEFAULTS = Map.of(
            "scanNoReadPct", 1.0,
            "receiveErrorsDay", 5,
            "asrsUtilisationPct", 85,
            "blockedLinesPct", 5,
            "putawayBacklogAgeMin", 120,
            "replenishmentOldestAgeMin", 120);

    private final SystemConfigurationRepository config;
    private final ObjectMapper mapper;

    public AlertThresholdsService(SystemConfigurationRepository config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    /** The baked defaults merged with any stored overrides (stored values win). */
    @Transactional(readOnly = true)
    public Map<String, Object> thresholds() {
        Map<String, Object> merged = new LinkedHashMap<>(DEFAULTS);
        config.findById(ALERT_THRESHOLDS_KEY)
                .map(SystemConfiguration::getValue)
                .ifPresent(json -> merged.putAll(parse(json)));
        return merged;
    }

    /**
     * Persist the given thresholds as overrides and return the effective set (defaults + stored).
     * Storing the full object (not just diffs) keeps the row authoritative and round-trips exactly.
     */
    @Transactional
    public Map<String, Object> save(Map<String, Object> thresholds) {
        Map<String, Object> toStore = new LinkedHashMap<>(DEFAULTS);
        if (thresholds != null) {
            toStore.putAll(thresholds);
        }
        SystemConfiguration c = config.findById(ALERT_THRESHOLDS_KEY)
                .orElseGet(() -> new SystemConfiguration(ALERT_THRESHOLDS_KEY, "{}"));
        c.setValue(write(toStore));
        config.save(c);
        return thresholds();
    }

    private Map<String, Object> parse(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            // Best-effort: a corrupt stored value falls back to the baked defaults.
            return Map.of();
        }
    }

    private String write(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise alert thresholds", e);
        }
    }
}
