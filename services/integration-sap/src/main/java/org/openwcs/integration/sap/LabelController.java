package org.openwcs.integration.sap;

import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Host label-barcode allocation (anti-corruption layer for SAP). openWCS asks the host for a
 * unique label barcode per shipper after cubing has produced the cartons. This skeleton returns
 * a deterministic SAP-style barcode; wire the real OData/BAPI/RFC call where indicated.
 */
@RestController
@RequestMapping("/api/integration/sap")
public class LabelController {

    @PostMapping("/labels")
    public LabelBarcodeResponse allocateBarcode(@RequestBody LabelBarcodeRequest request) {
        // TODO: call SAP (OData/BAPI) to allocate a carrier/label number for this shipper.
        String service = request.serviceCode() == null ? "STD" : request.serviceCode();
        String barcode = "SAP" + service + "-" + request.orderRef() + "-" + request.seqNo();
        return new LabelBarcodeResponse(barcode);
    }

    /** A request for one shipper's label barcode (carton {@code seqNo} within the order). */
    public record LabelBarcodeRequest(
            String orderRef, UUID warehouseId, String serviceCode, String routeCode, int seqNo) {
    }

    public record LabelBarcodeResponse(String barcode) {
    }
}
