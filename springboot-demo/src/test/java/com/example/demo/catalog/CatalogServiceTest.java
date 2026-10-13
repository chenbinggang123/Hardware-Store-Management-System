package com.example.demo.catalog;

import com.example.demo.catalog.dto.CatalogPageView;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.Product;
import com.example.demo.entity.SalesOrder;
import com.example.demo.entity.SalesOrderItem;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.PurchaseOrderRepository;
import com.example.demo.repository.SalesOrderRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogServiceTest {
    @Test
    void buildsFrequentAndAttentionViewsWithPagination() {
        ProductRepository productRepository = mock(ProductRepository.class);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        SalesOrderRepository salesOrderRepository = mock(SalesOrderRepository.class);
        PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);

        Product drill = product(1L, "冲击钻", new BigDecimal("268"));
        Product hammer = product(2L, "羊角锤", null);
        Product pipe = product(3L, "PVC 管", new BigDecimal("12.5"));
        Inventory drillInventory = inventory(11L, 1L, 20, 5);
        Inventory hammerInventory = inventory(12L, 2L, 0, 5);

        SalesOrder order = new SalesOrder();
        order.setOrderTime(LocalDateTime.of(2026, 10, 12, 9, 0));
        order.setItems(List.of(salesItem(1L), salesItem(1L), salesItem(2L)));

        when(productRepository.findAll()).thenReturn(List.of(drill, hammer, pipe));
        when(inventoryRepository.findAll()).thenReturn(List.of(drillInventory, hammerInventory));
        when(salesOrderRepository.findAll()).thenReturn(List.of(order));
        when(purchaseOrderRepository.findAll()).thenReturn(List.of());

        CatalogService service = new CatalogService(productRepository, inventoryRepository,
                salesOrderRepository, purchaseOrderRepository);
        CatalogPageView frequent = service.list("frequent", null, 0, 1);
        assertThat(frequent.totalElements()).isEqualTo(2);
        assertThat(frequent.totalPages()).isEqualTo(2);
        assertThat(frequent.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(1L);
            assertThat(item.activityCount()).isEqualTo(2);
            assertThat(item.stockState()).isEqualTo("IN_STOCK");
        });

        CatalogPageView attention = service.list("attention", null, 0, 20);
        assertThat(attention.items()).extracting(item -> item.id()).containsExactly(2L, 3L);
        assertThat(attention.items().get(0).attentionReasons()).containsExactly("缺货", "售价未填写");
        assertThat(attention.attentionCount()).isEqualTo(2);
        assertThat(attention.inventoryRecorded()).isEqualTo(2);
    }

    private Product product(Long id, String name, BigDecimal price) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setRetailPrice(price);
        product.setStock(0);
        return product;
    }

    private Inventory inventory(Long id, Long productId, int quantity, int threshold) {
        Inventory inventory = new Inventory();
        inventory.setId(id);
        inventory.setProductId(productId);
        inventory.setQuantity(quantity);
        inventory.setWarningThreshold(threshold);
        return inventory;
    }

    private SalesOrderItem salesItem(Long productId) {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductId(productId);
        item.setQuantity(1);
        return item;
    }
}
