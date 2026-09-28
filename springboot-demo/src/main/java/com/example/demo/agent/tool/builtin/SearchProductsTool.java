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
public class SearchProductsTool implements AgentTool {
    private final ProductService productService;

    public SearchProductsTool(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "search_products",
                "按名称、条码、规格或厂商搜索已上架商品",
                AgentToolRisk.R0_READ_ONLY,
                Map.of(
                        "type", "object",
                        "properties", Map.of("keyword", Map.of("type", "string", "description", "商品名称、条码、规格或厂商关键词")),
                        "required", List.of("keyword"),
                        "additionalProperties", false));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        String keyword = stringValue(arguments.get("keyword"));
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("商品搜索关键词不能为空");
        }
        List<Product> products = productService.getAllProducts(keyword, 1);
        return products.stream().limit(10).map(product -> Map.of(
                "id", product.getId(),
                "name", safe(product.getName()),
                "spec", safe(product.getSpec()),
                "unit", safe(product.getUnit()),
                "retailPrice", product.getRetailPrice() == null ? 0 : product.getRetailPrice()
        )).toList();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
