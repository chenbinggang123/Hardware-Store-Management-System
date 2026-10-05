package com.example.demo.price;

import com.example.demo.entity.Product;
import com.example.demo.price.entity.ProductPriceHistory;
import com.example.demo.price.repository.ProductPriceHistoryRepository;
import com.example.demo.price.service.ProductPriceHistoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductPriceHistoryServiceTest {
    @Test
    void recordsSupplierPriceChangeWithEffectiveDateAndPercentage() {
        ProductPriceHistoryRepository repository = mock(ProductPriceHistoryRepository.class);
        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        ProductPriceHistoryService service = new ProductPriceHistoryService(repository);
        Product before = product(18L, "10.00");
        Product after = product(18L, "12.50");

        service.recordMasterChanges(before, after, 7L, LocalDate.of(2026, 10, 1), 3L,
                "EXCEL_SUPPLIER_PRICE", 21L, 31L, "row-result:31", "excel:21:31:6");

        ArgumentCaptor<ProductPriceHistory> saved = ArgumentCaptor.forClass(ProductPriceHistory.class);
        verify(repository).save(saved.capture());
        ProductPriceHistory history = saved.getValue();
        assertEquals(7L, history.getSupplierId());
        assertEquals("COST", history.getPriceType());
        assertEquals(new BigDecimal("25.0000"), history.getChangePercent());
        assertEquals(LocalDate.of(2026, 10, 1), history.getEffectiveDate());
    }

    @Test
    void recordsPriceBeingClearedInsteadOfSilentlyDroppingIt() {
        ProductPriceHistoryRepository repository = mock(ProductPriceHistoryRepository.class);
        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        ProductPriceHistoryService service = new ProductPriceHistoryService(repository);

        service.recordMasterChanges(product(8L, "9.00"), product(8L, null), 4L,
                "MANUAL_PRODUCT_EDIT", null, 8L, null, "manual:8:one");

        ArgumentCaptor<ProductPriceHistory> saved = ArgumentCaptor.forClass(ProductPriceHistory.class);
        verify(repository).save(saved.capture());
        assertNull(saved.getValue().getAfterPrice());
        assertNull(saved.getValue().getChangePercent());
    }

    @Test
    void purchaseObservationIsIdempotent() {
        ProductPriceHistoryRepository repository = mock(ProductPriceHistoryRepository.class);
        ProductPriceHistory existing = new ProductPriceHistory();
        existing.setProductId(5L);
        existing.setPriceType("PURCHASE");
        existing.setAfterPrice(new BigDecimal("8.20"));
        when(repository.findByIdempotencyKey("purchase-stock-in:40:2:PURCHASE"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        ProductPriceHistoryService service = new ProductPriceHistoryService(repository);

        service.recordPurchasePrice(5L, 6L, new BigDecimal("8.00"), new BigDecimal("8.20"),
                40L, "2", 9L, LocalDate.of(2026, 10, 2));
        service.recordPurchasePrice(5L, 6L, new BigDecimal("8.00"), new BigDecimal("8.20"),
                40L, "2", 9L, LocalDate.of(2026, 10, 2));

        verify(repository, times(1)).save(org.mockito.ArgumentMatchers.any(ProductPriceHistory.class));
    }

    private Product product(Long id, String cost) {
        Product value = new Product();
        value.setId(id);
        value.setCostPrice(cost == null ? null : new BigDecimal(cost));
        return value;
    }
}
