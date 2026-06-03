package org.openwcs.gtp.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.StationNode;

/** A station and its configured nodes. */
public record StationView(
        UUID id,
        UUID warehouseId,
        String code,
        String mode,
        String status,
        List<NodeView> nodes) {

    public static StationView from(GtpStation s, List<StationNode> nodes) {
        return new StationView(s.getId(), s.getWarehouseId(), s.getCode(), s.getMode(), s.getStatus(),
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
