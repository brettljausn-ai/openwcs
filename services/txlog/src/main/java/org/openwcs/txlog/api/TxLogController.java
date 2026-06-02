package org.openwcs.txlog.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.openwcs.txlog.domain.Event;
import org.openwcs.txlog.service.TransactionLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Append, query, and replay the transaction log (build.md §4 txlog, §5.2). */
@RestController
@RequestMapping("/api/txlog")
public class TxLogController {

    private final TransactionLogService service;

    public TxLogController(TransactionLogService service) {
        this.service = service;
    }

    @PostMapping("/events")
    public ResponseEntity<EventView> append(@Valid @RequestBody AppendEventRequest request) {
        Event event = service.append(request.toCommand());
        return ResponseEntity
                .created(URI.create("/api/txlog/events/" + event.getEventId()))
                .body(EventView.from(event));
    }

    /** Replay one aggregate's stream in sequence order. */
    @GetMapping("/streams/{streamId}/events")
    public List<EventView> streamEvents(@PathVariable String streamId) {
        return service.streamEvents(streamId).stream().map(EventView::from).toList();
    }

    /** Global replay feed after a position cursor (build.md §5.4). */
    @GetMapping("/events")
    public List<EventView> feed(
            @RequestParam(defaultValue = "0") long afterPosition,
            @RequestParam(defaultValue = "100") int limit) {
        return service.feed(afterPosition, limit).stream().map(EventView::from).toList();
    }
}
