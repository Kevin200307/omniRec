package io.omnirec.web.controller;

import io.omnirec.core.model.SearchResult;
import io.omnirec.core.service.PersonalizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Backs <SearchBar> from @omnirec/react-ui. Which SearchProvider bean(s)
 * are active — a real search starter, or the in-memory fallback — is
 * entirely a config decision this controller never knows about.
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
