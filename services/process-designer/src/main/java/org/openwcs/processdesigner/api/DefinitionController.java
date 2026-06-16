package org.openwcs.processdesigner.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.util.List;
import org.openwcs.processdesigner.domain.ProcessDefinition;
import org.openwcs.processdesigner.service.DefinitionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Definition API (spec §12). Reads return summaries or the full model JSON; writes (create draft /
 * edit draft / publish / archive) are gated by {@code PROCESS_DESIGN_EDIT} in {@link RbacFilter}.
 * Publish is audited with the forwarded {@code X-Auth-User}.
 */
@RestController
@RequestMapping("/api/process-designer")
public class DefinitionController {

    private final DefinitionService service;

    public DefinitionController(DefinitionService service) {
        this.service = service;
    }

    /** List definition summaries, optionally filtered by status (DRAFT|ACTIVE|ARCHIVED). */
    @GetMapping("/defs")
    public List<DefinitionSummary> list(@RequestParam(required = false) String status) {
        return service.list(status).stream().map(DefinitionSummary::from).toList();
    }

    /** The full model JSON of one version. */
    @GetMapping("/defs/{key}/{version}")
    public JsonNode get(@PathVariable String key, @PathVariable int version) {
        return service.get(key, version).getJson();
    }

    /** The full model JSON of the ACTIVE version for a key. */
    @GetMapping("/defs/{key}/active")
    public JsonNode active(@PathVariable String key) {
        return service.active(key).getJson();
    }

    /** Distinct process keys + active version + title/icon, for the operator menu + designer list. */
    @GetMapping("/processes")
    public List<ProcessMenuEntry> processes() {
        return service.processes();
    }

    /** Create a DRAFT (auto-incremented version per key). */
    @PostMapping("/defs")
    public ResponseEntity<DefinitionSummary> create(@Valid @RequestBody CreateDefinitionRequest req) {
        ProcessDefinition def = service.createDraft(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(DefinitionSummary.from(def));
    }

    /** Replace a DRAFT's model JSON (409 if not DRAFT). */
    @PutMapping("/defs/{key}/{version}")
    public JsonNode update(@PathVariable String key, @PathVariable int version, @RequestBody JsonNode json) {
        return service.updateDraft(key, version, json).getJson();
    }

    /** Publish a DRAFT: validate (422 with problems if invalid), set ACTIVE, archive prior ACTIVE. */
    @PostMapping("/defs/{key}/{version}/publish")
    public DefinitionSummary publish(@PathVariable String key, @PathVariable int version,
                                     @RequestHeader(name = "X-Auth-User", required = false) String actor) {
        return DefinitionSummary.from(service.publish(key, version, actor == null ? "system" : actor));
    }

    /** Archive a version. */
    @PostMapping("/defs/{key}/{version}/archive")
    public DefinitionSummary archive(@PathVariable String key, @PathVariable int version) {
        return DefinitionSummary.from(service.archive(key, version));
    }
}
