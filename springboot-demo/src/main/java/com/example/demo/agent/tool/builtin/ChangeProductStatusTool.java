package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.entity.Product;
import com.example.demo.service.ProductService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ChangeProductStatusTool implements AgentTool {
    private final ProductService productService;

    public ChangeProductStatusTool(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "change_product_status",
                "将指定商品上架或下架",
                AgentToolRisk.R2_WRITE,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "productId", Map.of("type", "integer", "description", "商品 ID"),
                                "status", Map.of("type", "integer", "enum", List.of(0, 1), "description", "1 为上架，0 为下架")),
                        "required", List.of("productId", "status")),
                "确认修改商品状态");
    }

    @Override
    public Object approvalPreview(AgentToolContext context, Map<String, Object> arguments) {
        Product product = product(arguments);
        int status = requiredStatus(arguments);
        return Map.of(
                "productId", product.getId(),
                "productName", safe(product.getName()),
                "barcode", safe(product.getBarcode()),
                "currentStatusText", statusText(product.getStatus()),
                "statusText", statusText(status),
                "effect", "商品将被" + statusText(status));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        Product product = product(arguments);
        int status = requiredStatus(arguments);
        productService.changeProductStatus(product.getId(), status);
        return Map.of(
                "productId", product.getId(),
                "productName", safe(product.getName()),
                "status", status,
                "statusText", statusText(status));
    }

    private Product product(Map<String, Object> arguments) {
        Long id = requiredLong(arguments, "productId");
        return productService.getProductById(id)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在：" + id));
    }

    private int requiredStatus(Map<String, Object> arguments) {
        long value = requiredLong(arguments, "status");
        if (value != 0 && value != 1) throw new IllegalArgumentException("商品状态只能是 0 或 1");
        return (int) value;
    }

    private Long requiredLong(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(name + " 必须是整数");
        return number.longValue();
    }

    private String statusText(Integer status) {
        return Integer.valueOf(1).equals(status) ? "上架" : "下架";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
