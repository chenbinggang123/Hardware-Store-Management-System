package com.example.demo.catalog;

import com.example.demo.catalog.dto.CatalogPageView;
import com.example.demo.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/catalog")
public class CatalogController {
    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<CatalogPageView> list(@RequestParam(defaultValue = "frequent") String view,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "") String factory,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(view, keyword, factory, page, size));
    }
}
