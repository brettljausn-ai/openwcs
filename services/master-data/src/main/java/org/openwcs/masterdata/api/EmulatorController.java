package org.openwcs.masterdata.api;

import org.openwcs.common.security.AccessControl;
import org.openwcs.masterdata.service.HardwareEmulatorService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hardware emulator mode. A global admin switch: when ON the system runs against simulated
 * equipment and the device adapters never connect to physical hardware; when OFF the adapters
 * use the real connection path. The flag lives in {@code system_configuration} and the Go
 * adapters poll {@code GET /api/master-data/emulator} to learn it. Reads are open; writes are
 * ADMIN-only (same pattern as demo mode).
 */
@RestController
@RequestMapping("/api/master-data/emulator")
public class EmulatorController {

    private final HardwareEmulatorService emulator;

    public EmulatorController(HardwareEmulatorService emulator) {
        this.emulator = emulator;
    }

    @GetMapping
    public EmulatorStatusView status() {
        return new EmulatorStatusView(emulator.isEnabled());
    }

    /** Turn emulator mode ON (admin-only). The system stops connecting to physical hardware. */
    @PostMapping("/enable")
    public EmulatorStatusView enable(@RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return new EmulatorStatusView(emulator.setEnabled(true));
    }

    /** Turn emulator mode OFF (admin-only). The adapters resume the real connection path. */
    @PostMapping("/disable")
    public EmulatorStatusView disable(@RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return new EmulatorStatusView(emulator.setEnabled(false));
    }

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Hardware emulator mode is administered by ADMIN only.");
        }
    }

    /** Current emulator-mode state. */
    public record EmulatorStatusView(boolean enabled) {
    }
}
