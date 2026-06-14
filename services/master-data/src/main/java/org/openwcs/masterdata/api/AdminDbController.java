package org.openwcs.masterdata.api;

import java.util.List;
import org.openwcs.masterdata.service.AdminDbService;
import org.openwcs.masterdata.service.AdminDbService.QueryResult;
import org.openwcs.masterdata.service.AdminDbService.SchemaView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin database console (Administration → Database in the UI). Lets a user browse every schema in
 * the shared PostgreSQL database and run read-only SELECT queries.
 *
 * <p>Who may reach it is governed by <strong>screen access</strong> for the {@code admin-database}
 * screen (ADMIN by default; an admin may grant other roles/users): the gateway only forwards a
 * request to these endpoints when the caller has at least READ on that screen (build.md §4.8,
 * {@code ScreenWriteCatalog} access rules). The console is SELECT-only, so READ is sufficient. All
 * query safety lives in {@link AdminDbService} (SELECT-only validation + read-only transaction +
 * timeout + row cap), regardless of who runs it.
 */
@RestController
@RequestMapping("/api/master-data/admin/db")
public class AdminDbController {

    private final AdminDbService adminDb;

    public AdminDbController(AdminDbService adminDb) {
        this.adminDb = adminDb;
    }

    /** Non-system schemas with their tables and column metadata. */
    @GetMapping("/schemas")
    public List<SchemaView> schemas() {
        return adminDb.listSchemas();
    }

    /** One read-only SELECT statement; {@code maxRows} caps the result (default 200, max 1000). */
    public record QueryRequest(String sql, Integer maxRows) {
    }

    /** Execute a single SELECT. Anything else is rejected with 400. */
    @PostMapping("/query")
    public QueryResult query(
            @RequestBody QueryRequest request,
            @RequestHeader(name = "X-Auth-User", required = false) String user) {
        return adminDb.runQuery(request.sql(), request.maxRows(), user == null ? "(unknown)" : user);
    }
}
