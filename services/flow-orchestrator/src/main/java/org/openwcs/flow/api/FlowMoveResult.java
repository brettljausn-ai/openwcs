package org.openwcs.flow.api;

import java.util.UUID;

/** Result of {@code POST /api/flow/moves}: the id of the dispatched device task carrying the HU. */
public record FlowMoveResult(UUID deviceTaskId) {
}
