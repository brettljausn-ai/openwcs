package org.openwcs.processdesigner.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import java.util.List;
import org.openwcs.processdesigner.domain.ProcessDefinition;
import org.openwcs.processdesigner.service.DefinitionService;
import org.openwcs.processdesigner.service.ScriptGovernance;
import org.openwcs.processdesigner.task.TaskRegistry;
import org.openwcs.processdesigner.task.TaskSpec;
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
    private final TaskRegistry taskRegistry;
    private final ScriptGovernance scriptGovernance;
    private final ObjectMapper mapper;

    public DefinitionController(DefinitionService service, TaskRegistry taskRegistry,
                               ScriptGovernance scriptGovernance, ObjectMapper mapper) {
        this.service = service;
        this.taskRegistry = taskRegistry;
        this.scriptGovernance = scriptGovernance;
        this.mapper = mapper;
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

    /** The curated task catalog (type, label, inputs, outputs) for the designer's task picker. */
    @GetMapping("/tasks")
    public List<TaskSpec> tasks() {
        return taskRegistry.catalog();
    }

    /** Create a DRAFT (auto-incremented version per key). */
    @PostMapping("/defs")
    public ResponseEntity<DefinitionSummary> create(@Valid @RequestBody CreateDefinitionRequest req,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        // Script-step governance (spec §7.2): if the new draft carries a script step, require the
        // scripting flag (422) and PROCESS_SCRIPT_AUTHOR (403). Most creates carry no steps -> no-op.
        if (req.steps() != null) {
            ObjectNode probe = mapper.createObjectNode();
            probe.set("steps", req.steps());
            scriptGovernance.enforceSave(probe, roles);
        }
        ProcessDefinition def = service.createDraft(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(DefinitionSummary.from(def));
    }

    /**
     * Duplicate a version into a fresh DRAFT of the same key (clone its model JSON). Lets a user
     * iterate from a published version. Returns the new DRAFT summary.
     */
    @PostMapping("/defs/{key}/{version}/duplicate")
    public ResponseEntity<DefinitionSummary> duplicate(@PathVariable String key, @PathVariable int version) {
        ProcessDefinition def = service.duplicate(key, version);
        return ResponseEntity.status(HttpStatus.CREATED).body(DefinitionSummary.from(def));
    }

    /**
     * Import a full model document as a new DRAFT for its processKey (validates the model shape;
     * ignores/overrides any incoming version/status). Lets definitions move between environments.
     */
    @PostMapping("/defs/import")
    public ResponseEntity<DefinitionSummary> importDefinition(@RequestBody JsonNode body,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        // Imported models with a script step are subject to the same flag/permission gates.
        scriptGovernance.enforceSave(body, roles);
        ProcessDefinition def = service.importDefinition(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(DefinitionSummary.from(def));
    }

    /** Replace a DRAFT's model JSON (409 if not DRAFT). */
    @PutMapping("/defs/{key}/{version}")
    public JsonNode update(@PathVariable String key, @PathVariable int version, @RequestBody JsonNode json,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        // Saving a draft that carries a script step requires the flag (422) + permission (403).
        scriptGovernance.enforceSave(json, roles);
        return service.updateDraft(key, version, json).getJson();
    }

    /** Publish a DRAFT: validate (422 with problems if invalid), set ACTIVE, archive prior ACTIVE. */
    @PostMapping("/defs/{key}/{version}/publish")
    public DefinitionSummary publish(@PathVariable String key, @PathVariable int version,
                                     @RequestHeader(name = "X-Auth-User", required = false) String actor,
                                     @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        // Re-check the script gates at publish (the flag could have been turned off after the draft
        // was saved, or the actor may lack PROCESS_SCRIPT_AUTHOR); publish also validates each script
        // parses in the sandbox (DefinitionService -> ScriptGovernance.parseProblems, 422).
        scriptGovernance.enforceSave(service.get(key, version).getJson(), roles);
        return DefinitionSummary.from(service.publish(key, version, actor == null ? "system" : actor));
    }

    /** Archive a version. */
    @PostMapping("/defs/{key}/{version}/archive")
    public DefinitionSummary archive(@PathVariable String key, @PathVariable int version) {
        return DefinitionSummary.from(service.archive(key, version));
    }
}
