package org.openwcs.integration.sap;

import java.util.List;
import org.openwcs.integration.sap.client.MasterDataClient;
import org.openwcs.integration.sap.client.MasterDataClient.RouteDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Route feed from the host (SAP) into the master-data Route catalog (build.md §16). The host is
 * the source of truth for routes; this upserts them by code so orders can reference a known
 * {@code routeCode}. Skeleton: routes are pushed in the request body; a real adapter would pull
 * them from SAP on a schedule / via IDoc.
 */
@RestController
@RequestMapping("/api/integration/sap")
public class RouteFeedController {

    private final MasterDataClient masterData;

    public RouteFeedController(MasterDataClient masterData) {
        this.masterData = masterData;
    }

    @PostMapping("/routes/sync")
    public RouteSyncResult syncRoutes(@RequestBody List<RouteDto> routes) {
        int created = 0;
        int updated = 0;
        for (RouteDto route : routes) {
            if (masterData.upsertRoute(route) == MasterDataClient.UpsertResult.CREATED) {
                created++;
            } else {
                updated++;
            }
        }
        return new RouteSyncResult(routes.size(), created, updated);
    }

    public record RouteSyncResult(int received, int created, int updated) {
    }
}
