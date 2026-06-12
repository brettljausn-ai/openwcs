package org.openwcs.masterdata.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.masterdata.domain.AttributeSchema;
import org.openwcs.masterdata.domain.Barcode;
import org.openwcs.masterdata.domain.BarcodeType;
import org.openwcs.masterdata.domain.HandlingUnitType;
import org.openwcs.masterdata.repo.AttributeSchemaRepository;
import org.openwcs.masterdata.repo.BarcodeRepository;
import org.openwcs.masterdata.repo.BarcodeTypeRepository;
import org.openwcs.common.security.AccessControl;
import org.openwcs.masterdata.repo.HandlingUnitTypeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reference catalogs and barcode resolution (build.md §6): attribute schemas,
 * barcode types, handling-unit types, and barcode lookup.
 */
@RestController
@RequestMapping("/api/master-data")
public class ReferenceDataController {

    private final AttributeSchemaRepository attributeSchemas;
    private final BarcodeTypeRepository barcodeTypes;
    private final HandlingUnitTypeRepository handlingUnitTypes;
    private final BarcodeRepository barcodes;

    public ReferenceDataController(AttributeSchemaRepository attributeSchemas, BarcodeTypeRepository barcodeTypes,
                                   HandlingUnitTypeRepository handlingUnitTypes, BarcodeRepository barcodes) {
        this.attributeSchemas = attributeSchemas;
        this.barcodeTypes = barcodeTypes;
        this.handlingUnitTypes = handlingUnitTypes;
        this.barcodes = barcodes;
    }

    // --------------------------------------------------------- AttributeSchemas
    @GetMapping("/attribute-schemas")
    public List<AttributeSchema> listAttributeSchemas(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String appliesTo) {
        if (warehouseId != null && appliesTo != null) {
            return attributeSchemas.findByWarehouseIdAndAppliesTo(warehouseId, appliesTo);
        }
        if (warehouseId != null) {
            return attributeSchemas.findByWarehouseId(warehouseId);
        }
        return attributeSchemas.findAll();
    }

    @PostMapping("/attribute-schemas")
    public ResponseEntity<AttributeSchema> createAttributeSchema(@RequestBody AttributeSchema body) {
        body.setId(null);
        AttributeSchema saved = attributeSchemas.save(body);
        return ResponseEntity.created(URI.create("/api/master-data/attribute-schemas/" + saved.getId())).body(saved);
    }

    @GetMapping("/attribute-schemas/{id}")
    public AttributeSchema getAttributeSchema(@PathVariable UUID id) {
        return attributeSchemas.findById(id).orElseThrow(() -> new NotFoundException("AttributeSchema", id));
    }

    // -------------------------------------------------------------- BarcodeTypes
    @GetMapping("/barcode-types")
    public List<BarcodeType> listBarcodeTypes() {
        return barcodeTypes.findAll();
    }

    @PostMapping("/barcode-types")
    public ResponseEntity<BarcodeType> createBarcodeType(@RequestBody BarcodeType body) {
        body.setId(null);
        return ResponseEntity.status(201).body(barcodeTypes.save(body));
    }

    // --------------------------------------------------------- HandlingUnitTypes
    @GetMapping("/handling-unit-types")
    public List<HandlingUnitType> listHandlingUnitTypes() {
        return handlingUnitTypes.findAll();
    }

    @GetMapping("/handling-unit-types/{id}")
    public HandlingUnitType getHandlingUnitType(@PathVariable UUID id) {
        return handlingUnitTypes.findById(id).orElseThrow(() -> new NotFoundException("HandlingUnitType", id));
    }

    @PostMapping("/handling-unit-types")
    public ResponseEntity<HandlingUnitType> createHandlingUnitType(@RequestBody HandlingUnitType body) {
        body.setId(null);
        return ResponseEntity.status(201).body(handlingUnitTypes.save(body));
    }

    @PutMapping("/handling-unit-types/{id}")
    public HandlingUnitType updateHandlingUnitType(@PathVariable UUID id, @RequestBody HandlingUnitType body) {
        handlingUnitTypes.findById(id).orElseThrow(() -> new NotFoundException("HandlingUnitType", id));
        body.setId(id);
        return handlingUnitTypes.save(body);
    }

    /** Archive a handling-unit type (ADMIN only). The caller must ensure no active HU still uses it. */
    @PutMapping("/handling-unit-types/{id}/archive")
    public HandlingUnitType archiveHandlingUnitType(
            @PathVariable UUID id, @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return setHuTypeStatus(id, "ARCHIVED");
    }

    /** Restore an archived handling-unit type (ADMIN only). */
    @PutMapping("/handling-unit-types/{id}/restore")
    public HandlingUnitType restoreHandlingUnitType(
            @PathVariable UUID id, @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return setHuTypeStatus(id, "ACTIVE");
    }

    private HandlingUnitType setHuTypeStatus(UUID id, String status) {
        HandlingUnitType t = handlingUnitTypes.findById(id)
                .orElseThrow(() -> new NotFoundException("HandlingUnitType", id));
        t.setStatus(status);
        return handlingUnitTypes.save(t);
    }

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Archiving handling-unit types is admin-only.");
        }
    }

    // ----------------------------------------------------------- Barcode lookup
    @GetMapping("/barcodes")
    public List<Barcode> findBarcodes(@RequestParam String value) {
        return barcodes.findByValue(value);
    }

    @DeleteMapping("/barcodes/{barcodeId}")
    public ResponseEntity<Void> deleteBarcode(@PathVariable UUID barcodeId, HttpServletRequest request) {
        // Barcodes are host-owned master data: interactive delete is rejected; host sync may remove them.
        HostManagedGuard.rejectInteractiveWrite(request, "Barcode");
        Barcode existing = barcodes.findById(barcodeId).orElseThrow(() -> new NotFoundException("Barcode", barcodeId));
        barcodes.delete(existing);
        return ResponseEntity.noContent().build();
    }
}
