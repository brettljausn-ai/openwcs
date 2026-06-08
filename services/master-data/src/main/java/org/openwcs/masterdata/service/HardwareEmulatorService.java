package org.openwcs.masterdata.service;

import org.openwcs.masterdata.domain.SystemConfiguration;
import org.openwcs.masterdata.repo.SystemConfigurationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hardware emulator mode: a single global flag in {@code system_configuration} (mirroring the
 * demo-mode flag). When ON, the Go device adapters simulate all equipment and never open a
 * connection to physical hardware; when OFF, the adapters use the real connection path. The
 * adapters poll {@link org.openwcs.masterdata.api.EmulatorController} to learn the current value.
 */
@Service
public class HardwareEmulatorService {

    static final String FLAG = "HARDWARE_EMULATOR_ENABLED";

    private final SystemConfigurationRepository config;

    public HardwareEmulatorService(SystemConfigurationRepository config) {
        this.config = config;
    }

    @Transactional(readOnly = true)
    public boolean isEnabled() {
        return config.findById(FLAG).map(c -> Boolean.parseBoolean(c.getValue())).orElse(false);
    }

    @Transactional
    public boolean setEnabled(boolean on) {
        SystemConfiguration c = config.findById(FLAG)
                .orElseGet(() -> new SystemConfiguration(FLAG, "false"));
        c.setValue(Boolean.toString(on));
        config.save(c);
        return on;
    }
}
