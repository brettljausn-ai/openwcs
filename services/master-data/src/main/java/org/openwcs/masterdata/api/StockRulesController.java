package org.openwcs.masterdata.api;

import org.openwcs.common.security.AccessControl;
import org.openwcs.masterdata.service.StockRulesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stock integrity rules. Global admin switches read by the services that fill handling units;
 * reads are open, writes are ADMIN-only (same pattern as demo mode and the hardware emulator).
 *
 * <p>Single SKU per compartment (default ON): one HU compartment holds exactly one SKU, so an
 * HU never carries more distinct SKUs than its type has compartments. GTP decanting reads this
 * flag and rejects cycles that would violate it while it is ON.
 */
@RestController
@RequestMapping("/api/master-data/stock-rules")
public class StockRulesController {

    private static final Logger log = LoggerFactory.getLogger(StockRulesController.class);

    private final StockRulesService rules;

    public StockRulesController(StockRulesService rules) {
        this.rules = rules;
    }

    @GetMapping
    public StockRulesView status() {
        return new StockRulesView(rules.singleSkuPerCompartment());
    }

    /** Turn the single-SKU-per-compartment rule ON (admin-only). */
    @PostMapping("/single-sku-per-compartment/enable")
    public StockRulesView enable(
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles,
            @RequestHeader(name = "X-Auth-User", required = false) String authUser) {
        requireAdmin(roles);
        boolean on = rules.setSingleSkuPerCompartment(true);
        log.info("stock rule single-sku-per-compartment enabled by {}: tote-filling flows"
                + " (GTP decanting) now reject cycles that would mix SKUs in one compartment", actor(authUser));
        return new StockRulesView(on);
    }

    /** Turn the single-SKU-per-compartment rule OFF (admin-only): compartments may mix SKUs. */
    @PostMapping("/single-sku-per-compartment/disable")
    public StockRulesView disable(
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles,
            @RequestHeader(name = "X-Auth-User", required = false) String authUser) {
        requireAdmin(roles);
        boolean on = rules.setSingleSkuPerCompartment(false);
        log.info("stock rule single-sku-per-compartment disabled by {}: compartments may now"
                + " deliberately mix SKUs", actor(authUser));
        return new StockRulesView(on);
    }

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Stock rules are administered by ADMIN only.");
        }
    }

    /** The acting identity for the audit log line; the gateway injects {@code X-Auth-User}. */
    private static String actor(String authUser) {
        return authUser == null || authUser.isBlank() ? "an unidentified admin" : authUser;
    }

    /** Current stock-rule states. */
    public record StockRulesView(boolean singleSkuPerCompartment) {
    }
}
