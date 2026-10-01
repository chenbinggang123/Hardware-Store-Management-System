package com.example.demo.agent;

import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.agent.tool.builtin.AdjustInventoryTool;
import com.example.demo.agent.tool.builtin.ChangeProductStatusTool;
import com.example.demo.agent.tool.builtin.CreateProductTool;
import com.example.demo.dto.InventoryAdjustRequest;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.Product;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.InventoryService;
import com.example.demo.service.ProductService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentBusinessToolsTest {
    private final AgentToolContext context = new AgentToolContext(50L, 7L);

    @Test
    void productStatusChangeRequiresApprovalAndUpdatesSelectedProduct() {
        ProductService productService = mock(ProductService.class);
        Product product = product(2L, "德力西空气开关", "690100000002");
        product.setStatus(0);
        when(productService.getProductById(2L)).thenReturn(Optional.of(product));
        ChangeProductStatusTool tool = new ChangeProductStatusTool(productService);

        assertEquals(AgentToolRisk.R2_WRITE, tool.definition().getRisk());
        Map<String, Object> preview = cast(tool.approvalPreview(context, Map.of("productId", 2, "status", 1)));
        assertEquals("上架", preview.get("statusText"));

        tool.execute(context, Map.of("productId", 2, "status", 1));
        verify(productService).changeProductStatus(2L, 1);
    }

    @Test
    void createProductAlsoCreatesInitialInventory() {
        ProductService productService = mock(ProductService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        when(productService.saveProduct(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            product.setId(9L);
            return product;
        });
        when(inventoryService.saveInventory(any(Inventory.class))).thenAnswer(invocation -> {
            Inventory inventory = invocation.getArgument(0);
            inventory.setId(19L);
            return inventory;
        });
        CreateProductTool tool = new CreateProductTool(productService, inventoryService);

        Map<String, Object> result = cast(tool.execute(context, Map.of(
                "name", "测试扳手", "barcode", "TEST-0009", "retailPrice", 18.5,
                "stock", 6, "unit", "把", "status", 1)));

        assertEquals(9L, result.get("productId"));
        assertEquals(19L, result.get("inventoryId"));
        ArgumentCaptor<Inventory> inventoryCaptor = ArgumentCaptor.forClass(Inventory.class);
        verify(inventoryService).saveInventory(inventoryCaptor.capture());
        assertEquals(6, inventoryCaptor.getValue().getQuantity());
    }

    @Test
    void inventoryAdjustmentUsesCurrentOperatorAndActualQuantity() {
        ProductRepository productRepository = mock(ProductRepository.class);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        InventoryService inventoryService = mock(InventoryService.class);
        Product product = product(2L, "德力西空气开关", "690100000002");
        Inventory inventory = new Inventory();
        inventory.setId(12L);
        inventory.setProductId(2L);
        inventory.setQuantity(8);
        when(productRepository.findById(2L)).thenReturn(Optional.of(product));
        when(inventoryRepository.findByProductId(2L)).thenReturn(Optional.of(inventory));
        when(inventoryService.adjustInventory(any(), any())).thenReturn(inventory);
        AdjustInventoryTool tool = new AdjustInventoryTool(productRepository, inventoryRepository, inventoryService);

        tool.execute(context, Map.of("productId", 2, "actualQuantity", 20, "reason", "门店盘点"));

        ArgumentCaptor<InventoryAdjustRequest> requestCaptor = ArgumentCaptor.forClass(InventoryAdjustRequest.class);
        verify(inventoryService).adjustInventory(org.mockito.ArgumentMatchers.eq(12L), requestCaptor.capture());
        assertEquals(20, requestCaptor.getValue().getActualQuantity());
        assertEquals(7L, requestCaptor.getValue().getOperatorId());
    }

    private Product product(Long id, String name, String barcode) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setBarcode(barcode);
        product.setRetailPrice(BigDecimal.TEN);
        product.setStock(0);
        return product;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
