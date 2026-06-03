package org.openwcs.gtp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.gtp.domain.DestinationDemand;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.StationNode;
import org.openwcs.gtp.repo.DestinationDemandRepository;
import org.openwcs.gtp.repo.GtpStationRepository;
import org.openwcs.gtp.repo.StationNodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the gtp context against PostgreSQL 16 — running Flyway V1 then Hibernate
 * {@code ddl-auto=validate}, so a clean start proves every entity mapping matches the migrated
 * schema. Round-trips the config entities (defaults, constraints, FKs).
 */
@SpringBootTest
@Testcontainers
class GtpContextTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    GtpStationRepository stations;

    @Autowired
    StationNodeRepository nodes;

    @Autowired
    DestinationDemandRepository demands;

    @Test
    void roundTripsConfigEntities() {
        UUID wh = UUID.randomUUID();

        GtpStation station = new GtpStation();
        station.setWarehouseId(wh);
        station.setCode("GTP-1");
        station.setMode("PUT_WALL");
        stations.save(station);

        StationNode stock = new StationNode();
        stock.setStationId(station.getId());
        stock.setRole("STOCK");
        stock.setCode("S1");
        stock.setPosition(0);
        nodes.save(stock);

        StationNode order = new StationNode();
        order.setStationId(station.getId());
        order.setRole("ORDER");
        order.setCode("A1");
        order.setPutLightId("light-A1");
        order.setOrderHuId(UUID.randomUUID());
        order.setPosition(1);
        nodes.save(order);

        DestinationDemand demand = new DestinationDemand();
        demand.setStationNodeId(order.getId());
        demand.setOrderRef("ORD-1");
        demand.setSkuId(UUID.randomUUID());
        demand.setRequestedQty(new BigDecimal("5"));
        demands.save(demand);

        assertThat(stations.findByWarehouseIdAndCode(wh, "GTP-1")).isPresent();
        assertThat(nodes.findByStationIdAndRole(station.getId(), "ORDER")).hasSize(1);
        assertThat(demands.findByStationNodeId(order.getId())).hasSize(1);
        assertThat(demands.findByStationNodeId(order.getId()).get(0).remaining())
                .isEqualByComparingTo("5");
    }
}
