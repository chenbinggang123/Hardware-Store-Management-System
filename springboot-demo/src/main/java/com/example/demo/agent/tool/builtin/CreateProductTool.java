package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.Product;
import com.example.demo.service.InventoryService;
import com.example.demo.service.ProductService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CreateProductTool implements AgentTool {
    private final ProductService productService;
    private final InventoryService inventoryService;

    public CreateProductTool(ProductService productService, InventoryService inventoryService) {
        this.productService = productService;
        this.inventoryService = inventoryService;
    }

    @Override
    public AgentToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string", "description", "商品名称"));
        properties.put("barcode", Map.of("type", "string", "description", "唯一商品条码"));
        properties.put("spec", Map.of("type", "string", "description", "规格"));
        properties.put("unit", Map.of("type", "string", "description", "单位，如个、把、箱"));
        properties.put("retailPrice", Map.of("type", "number", "minimum", 0, "description", "零售价"));
        properties.put("wholesalePrice", Map.of("type", "number", "minimum", 0, "description", "批发价"));
        properties.put("oldCustomerPrice", Map.of("type", "number", "minimum", 0, "description", "老客户价"));
        properties.put("costPrice", Map.of("type", "number", "minimum", 0, "description", "进价"));
        properties.put("stock", Map.of("type", "integer", "minimum", 0, "description", "初始库存，默认 0"));
        properties.put("locationId", Map.of("type", "string", "description", "库位"));
        properties.put("sourceFactory", Map.of("type", "string", "description", "源头厂商"));
        properties.put("status", Map.of("type", "integer", "enum", List.of(0, 1), "description", "1 上架，0 下架，默认 1"));
        return new AgentToolDefinition(
                "create_product",
                "新增商品并建立初始库存记录，可选择立即上架",
                AgentToolRisk.R2_WRITE,
                Map.of("type", "object", "additionalProperties", false,
                        "properties", properties, "required", List.of("name", "barcode")),
                "确认新增商品");
    }

    @Override
    public Object approvalPreview(AgentToolContext context, Map<String, Object> arguments) {
        Product product = fromArguments(arguments);
        Map<String, Object> preview = productView(product);
        preview.put("effect", "新增商品、建立初始库存，并将商品设为" + statusText(product.getStatus()));
        return preview;
    }

    @Override
    @Transactional
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        Product product = productService.saveProduct(fromArguments(arguments));
        Inventory inventory = new Inventory();
        inventory.setProductId(product.getId());
        inventory.setQuantity(product.getStock());
        inventory.setLocationId(product.getLocationId());
        inventory.setWarningThreshold(10);
        inventoryService.saveInventory(inventory);
        Map<String, Object> result = productView(product);
        result.put("productId", product.getId());
        result.put("inventoryId", inventory.getId());
        return result;
    }

    private Product fromArguments(Map<String, Object> arguments) {
        Product product = new Product();
        product.setName(requiredText(arguments, "name"));
        product.setBarcode(requiredText(arguments, "barcode"));
        product.setSpec(optionalText(arguments, "spec"));
        product.setUnit(optionalText(arguments, "unit"));
        product.setRetailPrice(decimal(arguments, "retailPrice"));
        product.setWholesalePrice(decimal(arguments, "wholesalePrice"));
        product.setOldCustomerPrice(decimal(arguments, "oldCustomerPrice"));
        product.setCostPrice(decimal(arguments, "costPrice"));
        product.setStock(integer(arguments, "stock", 0));
        product.setLocationId(optionalText(arguments, "locationId"));
        product.setSourceFactory(optionalText(arguments, "sourceFactory"));
        product.setStatus(integer(arguments, "status", 1));
        return product;
    }

    private Map<String, Object> productView(Product product) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productName", product.getName());
        result.put("barcode", product.getBarcode());
        result.put("spec", safe(product.getSpec()));
        result.put("unit", safe(product.getUnit()));
        result.put("retailPrice", product.getRetailPrice());
        result.put("stock", product.getStock());
        result.put("locationId", safe(product.getLocationId()));
        result.put("status", product.getStatus());
        result.put("statusText", statusText(product.getStatus()));
        return result;
    }

    private String requiredText(Map<String, Object> arguments, String name) {
        String value = optionalText(arguments, name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }

    private String optionalText(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value == null ? null : value.toString().trim();
    }

    private BigDecimal decimal(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) return null;
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(name + " 必须是数字"); }
    }

    private Integer integer(Map<String, Object> arguments, String name, int fallback) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(name + " 必须是整数");
        return number.intValue();
    }

    private String statusText(Integer status) { return Integer.valueOf(1).equals(status) ? "上架" : "下架"; }
    private String safe(String value) { return value == null ? "" : value; }
}
