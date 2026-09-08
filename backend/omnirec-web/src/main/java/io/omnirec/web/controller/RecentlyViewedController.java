package io.omnirec.web.controller;

import io.omnirec.core.service.PersonalizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Backs <RecentlyViewedCarousel>. Backed entirely by CacheProvider — works
 * with zero recommendation providers configured, which is exactly what
 * Phase 3's exit criteria requires.
 */
@RestController
public class RecentlyViewedController {

    private final PersonalizationService personalizationService;

    public RecentlyViewedController(PersonalizationService personalizationService) {
        this.personalizationService = personalizationService;
    }

    @GetMapping("/v1/recently-viewed")
    public Map<String, List<Map<String, Object>>> recentlyViewed(@RequestParam String tenantId, @RequestParam String userId) {
        List<Map<String, Object>> items = personalizationService.getRecentlyViewed(userId).stream()
                .map(productId -> Map.<String, Object>of("productId", productId))
                .toList();
        return Map.of("items", items);
    }
}
