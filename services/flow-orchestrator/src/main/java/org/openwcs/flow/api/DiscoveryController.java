package org.openwcs.flow.api;

import jakarta.validation.Valid;
import java.util.UUID;
import org.openwcs.flow.api.DiscoveryDtos.Discovery;
import org.openwcs.flow.api.DiscoveryDtos.ObservationRequest;
import org.openwcs.flow.service.DiscoveryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Topology learning API (build.md §8): a sniffer posts scan observations; the WCS infers a
 * candidate topology an admin can confirm in the editor. The actual defined-IP packet capture
 * lives in a sniffer adapter that normalizes telegrams into these observations.
 */
@RestController
@RequestMapping("/api/flow/conveyor")
public class DiscoveryController {

    private final DiscoveryService discovery;

    public DiscoveryController(DiscoveryService discovery) {
        this.discovery = discovery;
    }

    @PostMapping("/observations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void observe(@Valid @RequestBody ObservationRequest request) {
        discovery.ingest(request);
    }

    @GetMapping("/discovery")
    public Discovery discover(@RequestParam UUID warehouseId) {
        return discovery.discover(warehouseId);
    }
}
