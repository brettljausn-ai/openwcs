package org.openwcs.masterdata.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.masterdata.domain.Route;
import org.openwcs.masterdata.domain.ShippingService;
import org.openwcs.masterdata.repo.RouteRepository;
import org.openwcs.masterdata.repo.ShippingServiceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dispatch reference data: the shipping-service catalog (EXPRESS/STANDARD, by carrier) and the
 * route catalog (regions/depots). Outbound orders reference these by {@code code}; routes are
 * fed from a host system (see {@code hostRef}). CRUD here; other services look up by code.
 */
@RestController
@RequestMapping("/api/master-data")
public class DispatchCatalogController {

    private final ShippingServiceRepository services;
    private final RouteRepository routes;

    public DispatchCatalogController(ShippingServiceRepository services, RouteRepository routes) {
        this.services = services;
        this.routes = routes;
    }

    // --------------------------------------------------------------- Shipping services
    @GetMapping("/shipping-services")
    public List<ShippingService> listServices(@RequestParam(required = false) String code) {
        if (code != null) {
            return services.findByCode(code).map(List::of).orElseGet(List::of);
        }
        return services.findAll();
    }

    @PostMapping("/shipping-services")
    public ResponseEntity<ShippingService> createService(@RequestBody ShippingService body) {
        body.setId(null);
        ShippingService saved = services.save(body);
        return ResponseEntity.created(URI.create("/api/master-data/shipping-services/" + saved.getId())).body(saved);
    }

    @GetMapping("/shipping-services/{id}")
    public ShippingService getService(@PathVariable UUID id) {
        return services.findById(id).orElseThrow(() -> new NotFoundException("ShippingService", id));
    }

    @PutMapping("/shipping-services/{id}")
    public ShippingService updateService(@PathVariable UUID id, @RequestBody ShippingService body) {
        ShippingService existing = services.findById(id).orElseThrow(() -> new NotFoundException("ShippingService", id));
        body.setId(id);
        body.setVersion(existing.getVersion());
        return services.save(body);
    }

    @DeleteMapping("/shipping-services/{id}")
    public ResponseEntity<Void> deleteService(@PathVariable UUID id) {
        ShippingService existing = services.findById(id).orElseThrow(() -> new NotFoundException("ShippingService", id));
        existing.setStatus("ARCHIVED");
        services.save(existing);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------------- Routes
    @GetMapping("/routes")
    public List<Route> listRoutes(@RequestParam(required = false) String code) {
        if (code != null) {
            return routes.findByCode(code).map(List::of).orElseGet(List::of);
        }
        return routes.findAll();
    }

    @PostMapping("/routes")
    public ResponseEntity<Route> createRoute(@RequestBody Route body) {
        body.setId(null);
        Route saved = routes.save(body);
        return ResponseEntity.created(URI.create("/api/master-data/routes/" + saved.getId())).body(saved);
    }

    @GetMapping("/routes/{id}")
    public Route getRoute(@PathVariable UUID id) {
        return routes.findById(id).orElseThrow(() -> new NotFoundException("Route", id));
    }

    @PutMapping("/routes/{id}")
    public Route updateRoute(@PathVariable UUID id, @RequestBody Route body) {
        Route existing = routes.findById(id).orElseThrow(() -> new NotFoundException("Route", id));
        body.setId(id);
        body.setVersion(existing.getVersion());
        return routes.save(body);
    }

    @DeleteMapping("/routes/{id}")
    public ResponseEntity<Void> deleteRoute(@PathVariable UUID id) {
        Route existing = routes.findById(id).orElseThrow(() -> new NotFoundException("Route", id));
        existing.setStatus("ARCHIVED");
        routes.save(existing);
        return ResponseEntity.noContent().build();
    }
}
