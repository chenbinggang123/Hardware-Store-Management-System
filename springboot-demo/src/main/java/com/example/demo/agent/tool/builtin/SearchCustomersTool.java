package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.entity.Customer;
import com.example.demo.service.CustomerService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SearchCustomersTool implements AgentTool {
    private final CustomerService customerService;

    public SearchCustomersTool(CustomerService customerService) {
        this.customerService = customerService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "search_customers",
                "按姓名或手机号搜索客户",
                AgentToolRisk.R0_READ_ONLY,
                Map.of(
                        "type", "object",
                        "properties", Map.of("keyword", Map.of("type", "string", "description", "客户姓名或手机号关键词")),
                        "required", List.of("keyword"),
                        "additionalProperties", false));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        String keyword = arguments.get("keyword") == null ? null : arguments.get("keyword").toString().trim();
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("客户搜索关键词不能为空");
        }
        List<Customer> customers = customerService.getAllCustomers(keyword, null);
        return customers.stream().limit(10).map(customer -> Map.of(
                "id", customer.getId(),
                "name", safe(customer.getName()),
                "phone", maskPhone(customer.getPhone()),
                "type", safe(customer.getType()),
                "debt", customer.getDebt() == null ? 0 : customer.getDebt()
        )).toList();
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone == null ? "" : phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
