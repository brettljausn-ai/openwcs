package org.openwcs.gtp.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.DestinationDemand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DestinationDemandRepository extends JpaRepository<DestinationDemand, UUID> {

    List<DestinationDemand> findByStationNodeId(UUID stationNodeId);

    /**
     * Open demand for a SKU across all ORDER nodes of a station — the candidates a presented
     * stock HU is matched against. Most-needed first so the greedy put-list fills the biggest
     * shortfalls before exhausting the HU.
     */
    @Query("select d from DestinationDemand d, StationNode n "
            + "where d.stationNodeId = n.id and n.stationId = :stationId "
            + "and d.skuId = :skuId and d.status = 'OPEN' "
            + "and d.requestedQty > d.puttedQty "
            + "order by (d.requestedQty - d.puttedQty) desc, n.position asc")
    List<DestinationDemand> findOpenForStationAndSku(@Param("stationId") UUID stationId,
                                                     @Param("skuId") UUID skuId);
}
