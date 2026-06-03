package org.openwcs.integration.sap;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.integration.sap.client.HostApiClient;
import org.openwcs.integration.sap.client.MasterDataClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Translates SAP-shaped orders / inbound deliveries into the canonical openWCS Host API
 * (anti-corruption layer). SAP item materials are resolved to SKU ids via master-data; the
 * SAP envelope/field names are reshaped to the vendor-neutral contract. (Skeleton: accepts a
 * SAP-ish JSON; the real OData/BAPI/IDoc decode is wired here later.)
 */
@RestController
@RequestMapping("/api/integration/sap")
public class SapOrderController {

    private final HostApiClient hostApi;
    private final MasterDataClient masterData;

    public SapOrderController(HostApiClient hostApi, MasterDataClient masterData) {
        this.hostApi = hostApi;
        this.masterData = masterData;
    }

    @PostMapping("/orders")
    public void order(@RequestBody SapOrder sap) {
        Map<String, Object> host = new HashMap<>();
        host.put("orderRef", sap.salesOrder());
        host.put("warehouseId", sap.warehouseId());
        host.put("customerRef", sap.soldTo());
        host.put("serviceCode", sap.serviceCode());
        host.put("routeCode", sap.routeCode());
        if (sap.shipTo() != null) {
            SapShipTo s = sap.shipTo();
            Map<String, Object> shipTo = new HashMap<>();
            shipTo.put("name", s.name());
            shipTo.put("line1", s.street());
            shipTo.put("city", s.city());
            shipTo.put("postcode", s.postalCode());
            shipTo.put("country", s.country());
            host.put("shipTo", shipTo);
        }
        host.put("lines", lines(sap.items()));
        hostApi.createOrder(host);
    }

    @PostMapping("/asns")
    public void asn(@RequestBody SapAsn sap) {
        Map<String, Object> host = new HashMap<>();
        host.put("asnRef", sap.inboundDelivery());
        host.put("warehouseId", sap.warehouseId());
        host.put("supplierRef", sap.supplier());
        host.put("lines", lines(sap.items()));
        hostApi.createAsn(host);
    }

    private List<Map<String, Object>> lines(List<SapItem> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (items != null) {
            for (SapItem item : items) {
                UUID skuId = masterData.skuIdByCode(item.material());
                if (skuId == null) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Unknown material (no SKU with code " + item.material() + ")");
                }
                out.add(Map.of("skuId", skuId, "qty", item.quantity()));
            }
        }
        return out;
    }

    public record SapOrder(String salesOrder, UUID warehouseId, String soldTo, String serviceCode,
                           String routeCode, SapShipTo shipTo, List<SapItem> items) {
    }

    public record SapAsn(String inboundDelivery, UUID warehouseId, String supplier, List<SapItem> items) {
    }

    public record SapShipTo(String name, String street, String city, String postalCode, String country) {
    }

    public record SapItem(String material, BigDecimal quantity) {
    }
}
