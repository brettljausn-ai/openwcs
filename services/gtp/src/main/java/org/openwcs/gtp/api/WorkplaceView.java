package org.openwcs.gtp.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.OperatingMode;
import org.openwcs.gtp.domain.StationNode;

/**
 * A GTP station presented as an operator <em>workplace</em> for the ops console launcher: its
 * destination topology + supported operating modes, its nodes, and whether it currently has an
 * active operator session ({@code inUse}). Opening a workplace claims a session for it.
 */
public record WorkplaceView(
        UUID id,
        UUID warehouseId,
        String code,
        String mode,
        List<String> supportedModes,
        String status,
        boolean acceptingWork,
        boolean inUse,
        List<StationView.NodeView> nodes) {

    public static WorkplaceView from(GtpStation s, List<StationNode> nodes, boolean inUse) {
        return new WorkplaceView(s.getId(), s.getWarehouseId(), s.getCode(), s.getMode(),
                s.supportedModeSet().stream().map(OperatingMode::name).toList(), s.getStatus(),
                s.isAcceptingWork(), inUse, nodes.stream().map(StationView.NodeView::from).toList());
    }
}
