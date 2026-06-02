package org.openwcs.masterdata.api;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.openwcs.masterdata.domain.LabelTemplate;
import org.openwcs.masterdata.repo.LabelTemplateRepository;
import org.openwcs.masterdata.service.LabelRenderer;
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
 * Dispatch-label template catalog (designed in the admin UI) + a render seam that turns a
 * template plus field values into a print payload (ZPL/PDF, base64). Concrete printing is a
 * later device-adapter concern.
 */
@RestController
@RequestMapping("/api/master-data/label-templates")
public class LabelTemplateController {

    private final LabelTemplateRepository templates;
    private final LabelRenderer renderer;

    public LabelTemplateController(LabelTemplateRepository templates, LabelRenderer renderer) {
        this.templates = templates;
        this.renderer = renderer;
    }

    @GetMapping
    public List<LabelTemplate> list(@RequestParam(required = false) String code) {
        if (code != null) {
            return templates.findByCode(code).map(List::of).orElseGet(List::of);
        }
        return templates.findAll();
    }

    @PostMapping
    public ResponseEntity<LabelTemplate> create(@RequestBody LabelTemplate body) {
        body.setId(null);
        LabelTemplate saved = templates.save(body);
        return ResponseEntity.created(URI.create("/api/master-data/label-templates/" + saved.getId())).body(saved);
    }

    @GetMapping("/{id}")
    public LabelTemplate get(@PathVariable UUID id) {
        return templates.findById(id).orElseThrow(() -> new NotFoundException("LabelTemplate", id));
    }

    @PutMapping("/{id}")
    public LabelTemplate update(@PathVariable UUID id, @RequestBody LabelTemplate body) {
        LabelTemplate existing = templates.findById(id).orElseThrow(() -> new NotFoundException("LabelTemplate", id));
        body.setId(id);
        body.setVersion(existing.getVersion());
        return templates.save(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        LabelTemplate existing = templates.findById(id).orElseThrow(() -> new NotFoundException("LabelTemplate", id));
        existing.setStatus("ARCHIVED");
        templates.save(existing);
        return ResponseEntity.noContent().build();
    }

    /** Render a template to a print payload. Returns the format and base64-encoded bytes. */
    @PostMapping("/{id}/render")
    public RenderedLabel render(@PathVariable UUID id, @RequestBody(required = false) RenderLabelRequest request) {
        LabelTemplate template = templates.findById(id).orElseThrow(() -> new NotFoundException("LabelTemplate", id));
        LabelRenderer.Format format = parseFormat(request == null ? null : request.format());
        Map<String, String> data = request == null || request.data() == null ? Map.of() : request.data();
        LabelRenderer.Rendered rendered = renderer.render(template, data, format);
        return new RenderedLabel(rendered.format(),
                java.util.Base64.getEncoder().encodeToString(rendered.payload()));
    }

    private static LabelRenderer.Format parseFormat(String format) {
        if (format == null || format.isBlank()) {
            return LabelRenderer.Format.ZPL;
        }
        try {
            return LabelRenderer.Format.valueOf(format.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported label format: " + format + " (expected ZPL or PDF)");
        }
    }

    /** Rendered label: the format and the payload as base64. */
    public record RenderedLabel(String format, String payloadBase64) {
    }
}
