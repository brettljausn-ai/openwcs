package org.openwcs.masterdata.service;

import org.openwcs.masterdata.domain.SystemConfiguration;
import org.openwcs.masterdata.repo.SystemConfigurationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock integrity rules: global admin-toggleable flags in {@code system_configuration}
 * (mirroring demo mode and the hardware emulator). Currently one rule:
 *
 * <p><b>Single SKU per compartment</b> (default ON) — a handling-unit compartment holds exactly
 * one SKU, so an HU may never carry more distinct SKUs than its type has compartments (a
 * 1-compartment tote holds one SKU). Enforced at the flows that fill totes (GTP decanting);
 * admins can switch it off for operations that deliberately mix SKUs in a compartment.
 */
@Service
public class StockRulesService {

    static final String SINGLE_SKU_PER_COMPARTMENT = "SINGLE_SKU_PER_COMPARTMENT_ENABLED";

    private final SystemConfigurationRepository config;

    public StockRulesService(SystemConfigurationRepository config) {
        this.config = config;
    }

    /** Whether the single-SKU-per-compartment rule is ON. Defaults to ON when never set. */
    @Transactional(readOnly = true)
    public boolean singleSkuPerCompartment() {
        return config.findById(SINGLE_SKU_PER_COMPARTMENT)
                .map(c -> Boolean.parseBoolean(c.getValue()))
                .orElse(true);
    }

    @Transactional
    public boolean setSingleSkuPerCompartment(boolean on) {
        SystemConfiguration c = config.findById(SINGLE_SKU_PER_COMPARTMENT)
                .orElseGet(() -> new SystemConfiguration(SINGLE_SKU_PER_COMPARTMENT, "true"));
        c.setValue(Boolean.toString(on));
        config.save(c);
        return on;
    }
}
