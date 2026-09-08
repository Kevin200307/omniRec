package io.omnirec.web.controller;

import io.omnirec.core.model.SearchResult;
import io.omnirec.core.service.PersonalizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Backs <SearchBar> from @omnirec/react-ui. Whether this is served by
 * Algolia or the in-memory fallback is decided entirely by which
 * SearchProvider bean(s) are active — this controller never knows.
 */
@RestController
public class SearchController {

    private final PersonalizationService personalizationService;

    public SearchController(PersonalizationService personalizationService) {
        this.personalizationService = personalizationService;
    }

    @GetMapping("/v1/search")
    public SearchResult search(@RequestParam String tenantId, @RequestParam(required = false, defaultValue = "") String q) {
        return personalizationService.search(q, Map.of());
    }
}
