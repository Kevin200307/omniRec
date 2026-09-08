package io.omnirec.web.controller;

import io.omnirec.core.model.RecContext;
import io.omnirec.core.model.Recommendation;
import io.omnirec.core.service.PersonalizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Backs <RecommendationCarousel>. Dispatches through PersonalizationService, which fans out to every active RecommendationProvider and merges results. */
@RestController
public class RecommendationController {

    private final PersonalizationService personalizationService;

    public RecommendationController(PersonalizationService personalizationService) {
        this.personalizationService = personalizationService;
    }

    @GetMapping("/v1/recommendations")
    public Map<String, List<Recommendation>> recommendations(
            @RequestParam String tenantId,
            @RequestParam String userId,
            @RequestParam(required = false, defaultValue = "10") int numResults
    ) {
        List<Recommendation> items = personalizationService.getRecommendations(userId, new RecContext(numResults));
        return Map.of("items", items);
    }
}
