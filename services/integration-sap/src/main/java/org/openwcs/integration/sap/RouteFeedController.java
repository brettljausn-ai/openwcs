package org.openwcs.integration.sap;

import java.util.List;
import org.openwcs.integration.sap.client.MasterDataClient;
import org.openwcs.integration.sap.client.MasterDataClient.RouteDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(RouteFeedController.class);

    private final MasterDataClient masterData;

    public RouteFeedController(MasterDataClient masterData) {
        this.masterData = masterData;
    }

    @PostMapping("/routes/sync")
    public RouteSyncResult syncRoutes(@RequestBody List<RouteDto> routes) {
        int created = 0;
        int updated = 0;
        for (RouteDto route : routes) {
            MasterDataClient.UpsertResult result = masterData.upsertRoute(route);
            log.debug("SAP route feed: route {} ('{}', host ref {}) {} in master-data",
                    route.code(), route.name(), route.hostRef(), result);
            if (result == MasterDataClient.UpsertResult.CREATED) {
                created++;
            } else {
                updated++;
            }
        }
        log.info("SAP route feed synced into the master-data Route catalog: {} routes received, {} created, {} updated",
                routes.size(), created, updated);
        return new RouteSyncResult(routes.size(), created, updated);
    }

    public record RouteSyncResult(int received, int created, int updated) {
    }
}
