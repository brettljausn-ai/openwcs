package org.openwcs.masterdata.api;

import java.util.List;
import org.openwcs.common.security.AccessControl;
import org.openwcs.masterdata.service.AdminDbService;
import org.openwcs.masterdata.service.AdminDbService.QueryResult;
import org.openwcs.masterdata.service.AdminDbService.SchemaView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin database console (Administration → Database in the UI). Lets an ADMIN browse every
 * schema in the shared PostgreSQL database and run read-only SELECT queries. Both endpoints
 * are ADMIN-only via the gateway-forwarded {@code X-Auth-Roles} header (same pattern as
 * {@link DemoController} / {@link EmulatorController}); all write protection lives in
 * {@link AdminDbService} (SELECT-only validation + read-only transaction + timeout + row cap).
 */
@RestController
@RequestMapping("/api/master-data/admin/db")
public class AdminDbController {

    private final AdminDbService adminDb;

    public AdminDbController(AdminDbService adminDb) {
        this.adminDb = adminDb;
    }

    /** Non-system schemas with their tables and column metadata (admin-only). */
    @GetMapping("/schemas")
    public List<SchemaView> schemas(@RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return adminDb.listSchemas();
    }

    /** One read-only SELECT statement; {@code maxRows} caps the result (default 200, max 1000). */
    public record QueryRequest(String sql, Integer maxRows) {
    }

    /** Execute a single SELECT (admin-only). Anything else is rejected with 400. */
    @PostMapping("/query")
    public QueryResult query(
            @RequestBody QueryRequest request,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles,
            @RequestHeader(name = "X-Auth-User", required = false) String user) {
        requireAdmin(roles);
        return adminDb.runQuery(request.sql(), request.maxRows(), user == null ? "(unknown)" : user);
    }

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The database console is available to ADMIN only.");
        }
    }
}
