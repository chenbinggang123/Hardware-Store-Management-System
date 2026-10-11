package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.entity.Supplier;
import com.example.demo.service.SupplierService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ListSuppliersTool implements AgentTool {
    private final SupplierService supplierService;

    public ListSuppliersTool(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "list_suppliers",
                "查询系统中登记的供应商、厂家、厂商或源头厂家；可不传关键词列出全部并返回总数",
                AgentToolRisk.R0_READ_ONLY,
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "keyword", Map.of("type", "string", "description", "可选，供应商名称、联系人、电话或地址关键词")),
                        "required", List.of(),
                        "additionalProperties", false));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        String keyword = stringValue(arguments.get("keyword"));
        List<Map<String, Object>> suppliers = supplierService.getAllSuppliers(keyword).stream()
                .map(this::toView)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", keyword);
        result.put("count", suppliers.size());
        result.put("suppliers", suppliers);
        return result;
    }

    private Map<String, Object> toView(Supplier supplier) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", supplier.getId());
        view.put("name", safe(supplier.getName()));
        view.put("contact", safe(supplier.getContact()));
        view.put("phone", maskPhone(supplier.getPhone()));
        view.put("address", safe(supplier.getAddress()));
        view.put("remark", safe(supplier.getRemark()));
        return view;
    }

    private String stringValue(Object value) {
        if (value == null) return "";
        return value.toString().trim();
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return safe(phone);
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
