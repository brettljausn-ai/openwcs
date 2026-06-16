package org.openwcs.processdesigner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.openwcs.processdesigner.api.CreateDefinitionRequest;
import org.openwcs.processdesigner.api.NotFoundException;
import org.openwcs.processdesigner.api.ProcessMenuEntry;
import org.openwcs.processdesigner.domain.ProcessDefinition;
import org.openwcs.processdesigner.repo.ProcessDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Definition storage + versioning. Versions auto-increment per process key; create yields a DRAFT;
 * editing is only allowed while DRAFT; publish flips a DRAFT to ACTIVE and archives the prior ACTIVE
 * atomically (one ACTIVE per key, also enforced by a partial unique index). All writes run in a
 * transaction so the publish swap is atomic.
 */
@Service
public class DefinitionService {

    private static final Logger log = LoggerFactory.getLogger(DefinitionService.class);

    private final ProcessDefinitionRepository repo;
    private final DefinitionValidator validator;
    private final ObjectMapper mapper;

    public DefinitionService(ProcessDefinitionRepository repo, DefinitionValidator validator, ObjectMapper mapper) {
        this.repo = repo;
        this.validator = validator;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ProcessDefinition> list(String status) {
        if (status == null || status.isBlank()) {
            return repo.findAllByOrderByProcessKeyAscVersionDesc();
        }
        return repo.findByStatusOrderByProcessKeyAscVersionDesc(status.toUpperCase());
    }

    @Transactional(readOnly = true)
    public ProcessDefinition get(String key, int version) {
        return repo.findByProcessKeyAndVersion(key, version)
                .orElseThrow(() -> new NotFoundException("Definition " + key + " v" + version + " not found"));
    }

    @Transactional(readOnly = true)
    public ProcessDefinition active(String key) {
        return repo.findByProcessKeyAndStatus(key, ProcessDefinition.ACTIVE)
                .orElseThrow(() -> new NotFoundException("No active definition for process " + key));
    }

    @Transactional(readOnly = true)
    public Optional<ProcessDefinition> findActive(String key) {
        return repo.findByProcessKeyAndStatus(key, ProcessDefinition.ACTIVE);
    }

    /** Distinct process keys with their ACTIVE version (or null) + title/icon, for the operator menu. */
    @Transactional(readOnly = true)
    public List<ProcessMenuEntry> processes() {
        // Latest-first ordering means the first row per key is the freshest; the ACTIVE row (if any)
        // supplies the active version + canonical title/icon.
        Map<String, ProcessMenuEntry> byKey = new LinkedHashMap<>();
        for (ProcessDefinition d : repo.findAllByOrderByProcessKeyAscVersionDesc()) {
            ProcessMenuEntry existing = byKey.get(d.getProcessKey());
            boolean isActive = ProcessDefinition.ACTIVE.equals(d.getStatus());
            if (existing == null) {
                byKey.put(d.getProcessKey(), new ProcessMenuEntry(d.getProcessKey(),
                        isActive ? d.getVersion() : null, d.getTitle(), d.getIcon()));
            } else if (isActive && existing.activeVersion() == null) {
                // Prefer the ACTIVE row's metadata + version.
                byKey.put(d.getProcessKey(), new ProcessMenuEntry(d.getProcessKey(),
                        d.getVersion(), d.getTitle(), d.getIcon()));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    @Transactional
    public ProcessDefinition createDraft(CreateDefinitionRequest req) {
        int next = repo.maxVersionForKey(req.processKey()) + 1;

        ObjectNode json = mapper.createObjectNode();
        json.put("processKey", req.processKey());
        json.put("version", next);
        json.put("status", ProcessDefinition.DRAFT);
        json.put("title", req.title());
        if (req.icon() != null) {
            json.put("icon", req.icon());
        }
        json.set("dataSchema", req.dataSchema() != null ? req.dataSchema() : mapper.createArrayNode());
        json.set("steps", req.steps() != null ? req.steps() : mapper.createObjectNode());
        if (req.start() != null) {
            json.put("start", req.start());
        }

        ProcessDefinition def = new ProcessDefinition();
        def.setProcessKey(req.processKey());
        def.setVersion(next);
        def.setStatus(ProcessDefinition.DRAFT);
        def.setTitle(req.title());
        def.setIcon(req.icon());
        def.setJson(json);
        ProcessDefinition saved = repo.save(def);
        log.info("process definition draft created: {} v{} ('{}')", req.processKey(), next, req.title());
        return saved;
    }

    /** Replace a DRAFT's JSON body. 409 if the version is not DRAFT. */
    @Transactional
    public ProcessDefinition updateDraft(String key, int version, JsonNode json) {
        ProcessDefinition def = get(key, version);
        if (!ProcessDefinition.DRAFT.equals(def.getStatus())) {
            throw new IllegalStateException("Definition " + key + " v" + version + " is "
                    + def.getStatus() + ", only DRAFT can be edited");
        }
        // Keep the denormalised key/version/status authoritative; copy title/icon from the body.
        if (json instanceof ObjectNode obj) {
            obj.put("processKey", key);
            obj.put("version", version);
            obj.put("status", ProcessDefinition.DRAFT);
            JsonNode title = obj.get("title");
            if (title != null && title.isTextual()) {
                def.setTitle(title.asText());
            }
            JsonNode icon = obj.get("icon");
            def.setIcon(icon != null && icon.isTextual() ? icon.asText() : null);
        }
        def.setJson(json);
        return def;
    }

    /** Publish a DRAFT: validate, set ACTIVE, archive the prior ACTIVE atomically. */
    @Transactional
    public ProcessDefinition publish(String key, int version, String actor) {
        ProcessDefinition def = get(key, version);
        if (!ProcessDefinition.DRAFT.equals(def.getStatus())) {
            throw new IllegalStateException("Definition " + key + " v" + version + " is "
                    + def.getStatus() + ", only DRAFT can be published");
        }
        List<String> problems = validator.validate(def.getJson());
        if (!problems.isEmpty()) {
            throw new DefinitionInvalidException(problems);
        }

        // Archive the currently-active version first so the partial unique index never sees two ACTIVE.
        repo.findByProcessKeyAndStatus(key, ProcessDefinition.ACTIVE).ifPresent(prior -> {
            prior.setStatus(ProcessDefinition.ARCHIVED);
            stampStatus(prior);
            repo.saveAndFlush(prior);
            log.info("process definition archived on publish: {} v{}", key, prior.getVersion());
        });

        def.setStatus(ProcessDefinition.ACTIVE);
        def.setPublishedAt(Instant.now());
        def.setPublishedBy(actor);
        stampStatus(def);
        log.info("process definition published ACTIVE: {} v{} by {}", key, version, actor);
        return def;
    }

    @Transactional
    public ProcessDefinition archive(String key, int version) {
        ProcessDefinition def = get(key, version);
        if (ProcessDefinition.ARCHIVED.equals(def.getStatus())) {
            return def;
        }
        def.setStatus(ProcessDefinition.ARCHIVED);
        stampStatus(def);
        log.info("process definition archived: {} v{}", key, version);
        return def;
    }

    /** Reflect the new status into the stored JSON's denormalised status field. */
    private void stampStatus(ProcessDefinition def) {
        if (def.getJson() instanceof ObjectNode obj) {
            obj.put("status", def.getStatus());
            def.setJson(obj);
        }
    }
}
