package com.example.demo.catalog.dto;

import java.util.List;

public record CatalogPageView(
        List<CatalogItemView> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        int totalProducts,
        int inventoryRecorded,
        int attentionCount) {
}
