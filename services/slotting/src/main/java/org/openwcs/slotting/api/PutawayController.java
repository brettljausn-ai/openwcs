package org.openwcs.slotting.api;

import org.openwcs.slotting.service.PutawayService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Put-away: decide where an inbound handling unit should go (ADR 0003). */
@RestController
@RequestMapping("/api/slotting/putaway")
public class PutawayController {

    private final PutawayService putaway;

    public PutawayController(PutawayService putaway) {
        this.putaway = putaway;
    }

    @PostMapping
    public PutawayDecision assign(@RequestBody PutawayRequest request) {
        return putaway.assign(request);
    }
}
