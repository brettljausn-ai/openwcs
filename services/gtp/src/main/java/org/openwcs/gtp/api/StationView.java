package org.openwcs.gtp.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.OperatingMode;
import org.openwcs.gtp.domain.StationNode;

/** A station, its destination topology + supported operating modes, and its configured nodes. */
public record StationView(
        UUID id,
        UUID warehouseId,
        String code,
        String name,
        String mode,
        List<String> supportedModes,
        String status,
        int maxInTransitPicking,
        int maxInTransitOther,
        List<NodeView> nodes) {

    public static StationView from(GtpStation s, List<StationNode> nodes) {
        return new StationView(s.getId(), s.getWarehouseId(), s.getCode(), s.getName(), s.getMode(),
                s.supportedModeSet().stream().map(OperatingMode::name).toList(), s.getStatus(),
                s.getMaxInTransitPicking(), s.getMaxInTransitOther(),
                nodes.stream().map(NodeView::from).toList());
    }

    public record NodeView(
            UUID id,
            String role,
            String code,
            String putLightId,
            UUID locationId,
            UUID orderHuId,
            int position,
            String status) {

        public static NodeView from(StationNode n) {
            return new NodeView(n.getId(), n.getRole(), n.getCode(), n.getPutLightId(),
                    n.getLocationId(), n.getOrderHuId(), n.getPosition(), n.getStatus());
        }
    }
}
