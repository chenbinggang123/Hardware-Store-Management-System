package com.example.demo.price.api;

import com.example.demo.common.ApiResponse;
import com.example.demo.price.dto.PriceHistoryItem;
import com.example.demo.price.dto.PriceHistoryPage;
import com.example.demo.price.service.ProductPriceHistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductPriceHistoryController {
    private final ProductPriceHistoryService service;

    public ProductPriceHistoryController(ProductPriceHistoryService service) {
        this.service = service;
    }

    @GetMapping("/products/{productId}/price-history")
    public ApiResponse<PriceHistoryPage> byProduct(@PathVariable Long productId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.byProduct(productId, page, size));
    }

    @GetMapping("/suppliers/{supplierId}/price-history")
    public ApiResponse<PriceHistoryPage> bySupplier(@PathVariable Long supplierId,
                                                    @RequestParam(required = false) Long productId,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.bySupplier(supplierId, productId, page, size));
    }

    @GetMapping("/price-history/{historyId}")
    public ApiResponse<PriceHistoryItem> get(@PathVariable Long historyId) {
        return ApiResponse.ok(service.require(historyId));
    }
}
