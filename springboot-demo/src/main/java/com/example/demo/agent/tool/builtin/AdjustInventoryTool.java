package com.example.demo.agent.tool.builtin;

import com.example.demo.dto.InventoryAdjustRequest;
import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.Product;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.InventoryService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AdjustInventoryTool implements AgentTool {
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryService inventoryService;

    public AdjustInventoryTool(ProductRepository productRepository, InventoryRepository inventoryRepository,
                               InventoryService inventoryService) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryService = inventoryService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "adjust_inventory",
                "按商品 ID 将账面库存盘点调整为指定实际数量",
                AgentToolRisk.R2_WRITE,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "productId", Map.of("type", "integer", "description", "商品 ID"),
                                "actualQuantity", Map.of("type", "integer", "minimum", 0, "description", "盘点后的实际库存"),
                                "reason", Map.of("type", "string", "description", "调整原因")),
                        "required", List.of("productId", "actualQuantity", "reason")),
                "确认调整库存");
    }

    @Override
    public Object approvalPreview(AgentToolContext context, Map<String, Object> arguments) {
        Product product = product(arguments);
        Inventory inventory = inventoryRepository.findByProductId(product.getId()).orElse(null);
        int before = inventory == null || inventory.getQuantity() == null ? defaultInt(product.getStock()) : inventory.getQuantity();
        int after = nonNegativeQuantity(arguments);
        return preview(product, inventory, before, after, requiredText(arguments, "reason"));
    }

    @Override
    @Transactional
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        Product product = product(arguments);
        int after = nonNegativeQuantity(arguments);
        String reason = requiredText(arguments, "reason");
        Inventory inventory = inventoryRepository.findByProductId(product.getId()).orElse(null);
        int before = inventory == null || inventory.getQuantity() == null ? defaultInt(product.getStock()) : inventory.getQuantity();
        if (inventory == null) {
            inventory = new Inventory();
            inventory.setProductId(product.getId());
            inventory.setQuantity(after);
            inventory.setLocationId(product.getLocationId());
            inventory.setWarningThreshold(10);
            inventory = inventoryService.saveInventory(inventory);
        } else {
            InventoryAdjustRequest request = new InventoryAdjustRequest();
            request.setActualQuantity(after);
            request.setReason(reason);
            request.setOperatorId(context.getOperatorId());
            inventory = inventoryService.adjustInventory(inventory.getId(), request);
        }
        Map<String, Object> result = preview(product, inventory, before, after, reason);
        result.put("inventoryId", inventory.getId());
        return result;
    }

    private Map<String, Object> preview(Product product, Inventory inventory, int before, int after, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productId", product.getId());
        result.put("productName", product.getName());
        result.put("inventoryId", inventory == null ? "" : inventory.getId());
        result.put("beforeQuantity", before);
        result.put("actualQuantity", after);
        result.put("changeQuantity", after - before);
        result.put("reason", reason);
        result.put("effect", "库存将从 " + before + " 调整为 " + after);
        return result;
    }

    private Product product(Map<String, Object> arguments) {
        Long id = requiredLong(arguments, "productId");
        return productRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("商品不存在：" + id));
    }

    private int nonNegativeQuantity(Map<String, Object> arguments) {
        long value = requiredLong(arguments, "actualQuantity");
        if (value < 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("实际库存数量必须是非负整数");
        return (int) value;
    }

    private Long requiredLong(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(name + " 必须是整数");
        return number.longValue();
    }

    private String requiredText(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.toString().trim();
    }

    private int defaultInt(Integer value) { return value == null ? 0 : value; }
}
