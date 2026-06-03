package org.openwcs.slotting.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.domain.ReslotRecommendation;
import org.openwcs.slotting.repo.ReslotRecommendationRepository;
import org.openwcs.slotting.service.ReslotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Re-slotting: generate + list move recommendations (ADR 0003). */
@RestController
@RequestMapping("/api/slotting/reslot")
public class ReslotController {

    private final ReslotService reslot;
    private final ReslotRecommendationRepository recommendations;

    public ReslotController(ReslotService reslot, ReslotRecommendationRepository recommendations) {
        this.reslot = reslot;
        this.recommendations = recommendations;
    }

    /** Run re-slotting now — a single block if {@code blockId} is given, else all enabled blocks. */
    @PostMapping("/recommend")
    public List<ReslotRecommendation> recommend(@RequestParam UUID warehouseId,
                                                @RequestParam(required = false) UUID blockId) {
        return blockId != null
                ? reslot.recommendForBlock(warehouseId, blockId)
                : reslot.recommend(warehouseId);
    }

    @GetMapping("/recommendations")
    public List<ReslotRecommendation> list(@RequestParam UUID warehouseId,
                                           @RequestParam(defaultValue = "RECOMMENDED") String status) {
        return recommendations.findByWarehouseIdAndStatus(warehouseId, status);
    }
}
