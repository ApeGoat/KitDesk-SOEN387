package com.kitdesk.api;

import com.kitdesk.domain.CategoryCode;
import com.kitdesk.service.CatalogueService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Catalogue reads. Lists are wrapped in an object, as the contract requires. */
@RestController
public class CatalogueController {

    private final CatalogueService catalogue;

    public CatalogueController(CatalogueService catalogue) {
        this.catalogue = catalogue;
    }

    @GetMapping("/categories")
    public Map<String, Object> categories() {
        return Map.of("categories", catalogue.listCategories());
    }

    /** An unknown {@code category} value is a 400 (handled by GlobalExceptionHandler). */
    @GetMapping("/models")
    public Map<String, Object> models(@RequestParam(required = false) CategoryCode category) {
        return Map.of("models", catalogue.listModels(category));
    }

    @GetMapping("/models/{modelId}/units")
    public Map<String, Object> units(@PathVariable String modelId) {
        return Map.of("units", catalogue.listUnits(modelId));
    }
}
