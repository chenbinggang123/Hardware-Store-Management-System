package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.Product;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.ProductRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.List;

@Component
public class CheckInventoryTool implements AgentTool {
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public CheckInventoryTool(ProductRepository productRepository, InventoryRepository inventoryRepository) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "check_inventory",
                "按商品 ID 查询实时库存",
                AgentToolRisk.R0_READ_ONLY,
                Map.of(
                        "type", "object",
                        "properties", Map.of("productId", Map.of("type", "integer", "description", "商品 ID")),
                        "required", List.of("productId"),
                        "additionalProperties", false));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        Long productId = longValue(arguments.get("productId"));
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在"));
        Inventory inventory = inventoryRepository.findByProductId(productId).orElse(null);
        int quantity = inventory == null || inventory.getQuantity() == null ? 0 : inventory.getQuantity();
        return Map.of(
                "productId", productId,
                "productName", product.getName(),
                "quantity", quantity,
                "locationId", inventory == null || inventory.getLocationId() == null ? "" : inventory.getLocationId()
        );
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            throw new IllegalArgumentException("商品 ID 不能为空");
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("商品 ID 格式错误");
        }
    }
}
