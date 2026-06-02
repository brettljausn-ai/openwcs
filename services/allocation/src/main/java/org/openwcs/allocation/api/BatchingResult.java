package org.openwcs.allocation.api;

import java.util.List;

/** Result of building pick batches: the batches created and the orders that were not batched. */
public record BatchingResult(List<PickBatchView> batches, List<String> notBatched) {
}
