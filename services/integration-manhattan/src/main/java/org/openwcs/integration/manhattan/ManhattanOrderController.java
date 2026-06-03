package org.openwcs.integration.manhattan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.integration.manhattan.client.HostApiClient;
import org.openwcs.integration.manhattan.client.MasterDataClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Translates Manhattan Active orders / ASNs into the canonical openWCS Host API
 * (anti-corruption layer). Item ids are resolved to SKU ids via master-data and the Manhattan
 * envelope/field names are reshaped to the vendor-neutral contract.
 */
@RestController
@RequestMapping("/api/integration/manhattan")
public class ManhattanOrderController {

    private final HostApiClient hostApi;
    private final MasterDataClient masterData;

    public ManhattanOrderController(HostApiClient hostApi, MasterDataClient masterData) {
        this.hostApi = hostApi;
        this.masterData = masterData;
    }

    @PostMapping("/orders")
    public void order(@RequestBody ManhattanOrder mh) {
        Map<String, Object> host = new HashMap<>();
        host.put("orderRef", mh.orderId());
        host.put("warehouseId", mh.facilityId());
        host.put("customerRef", mh.customer());
        host.put("serviceCode", mh.serviceLevel());
        host.put("routeCode", mh.route());
        if (mh.shipToAddress() != null) {
            ManhattanAddress a = mh.shipToAddress();
            Map<String, Object> shipTo = new HashMap<>();
            shipTo.put("name", a.name());
            shipTo.put("line1", a.addressLine1());
            shipTo.put("line2", a.addressLine2());
            shipTo.put("city", a.city());
            shipTo.put("region", a.stateProvince());
            shipTo.put("postcode", a.postalCode());
            shipTo.put("country", a.countryCode());
            host.put("shipTo", shipTo);
        }
        host.put("lines", lines(mh.orderLines()));
        hostApi.createOrder(host);
    }

    @PostMapping("/asns")
    public void asn(@RequestBody ManhattanAsn mh) {
        Map<String, Object> host = new HashMap<>();
        host.put("asnRef", mh.asnId());
        host.put("warehouseId", mh.facilityId());
        host.put("supplierRef", mh.vendor());
        host.put("lines", lines(mh.asnLines()));
        hostApi.createAsn(host);
    }

    private List<Map<String, Object>> lines(List<ManhattanLine> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (lines != null) {
            for (ManhattanLine line : lines) {
                UUID skuId = masterData.skuIdByCode(line.itemId());
                if (skuId == null) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Unknown item (no SKU with code " + line.itemId() + ")");
                }
                out.add(Map.of("skuId", skuId, "qty", line.quantity()));
            }
        }
        return out;
    }

    public record ManhattanOrder(String orderId, UUID facilityId, String customer, String serviceLevel,
                                 String route, ManhattanAddress shipToAddress, List<ManhattanLine> orderLines) {
    }

    public record ManhattanAsn(String asnId, UUID facilityId, String vendor, List<ManhattanLine> asnLines) {
    }

    public record ManhattanAddress(String name, String addressLine1, String addressLine2, String city,
                                   String stateProvince, String postalCode, String countryCode) {
    }

    public record ManhattanLine(String itemId, BigDecimal quantity) {
    }
}
