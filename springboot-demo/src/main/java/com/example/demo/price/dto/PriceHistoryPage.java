package com.example.demo.price.dto;

import java.util.List;

public record PriceHistoryPage(int page, int size, long totalElements, int totalPages,
                               List<PriceHistoryItem> items) {
}
