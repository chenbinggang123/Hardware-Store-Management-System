package com.example.demo.agent.harness;

import com.example.demo.agent.model.AgentRoutingResult;
import com.example.demo.agent.tool.AgentToolDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The allow-listed capability-to-tool map. Model output is never treated as a tool name. */
@Component
public class AgentCapabilityRegistry {
    private final Map<String, Map<String, List<String>>> capabilities = new LinkedHashMap<>();

    public AgentCapabilityRegistry() {
        add("excel", "create", "generate_excel_workbook", "prepare_billing_document");
        add("excel", "modify_generated", "read_generated_file", "generate_excel_workbook", "prepare_billing_document");
        add("excel", "find", "list_generated_files");
        add("excel", "file_tasks", "list_file_tasks", "export_file_task_to_excel");
        add("quotes", "generate", "generate_quote_files");
        add("inventory", "query", "check_inventory", "search_products");
        add("inventory", "adjust", "search_products", "check_inventory", "adjust_inventory");
        add("products", "search", "search_products", "search_product_encyclopedia");
        add("products", "create", "create_product");
        add("sales", "customer_sales", "search_customers", "get_customer_sales_summary");
        add("sales", "create_draft", "search_customers", "search_products", "create_sales_draft");
        add("sales", "modify_draft", "get_sales_draft", "update_sales_draft");
        add("sales", "submit_draft", "get_sales_draft", "commit_sales_draft");
        add("finance", "summary", "get_daily_finance_summary", "query_accounts");
        add("finance", "create_document", "create_account_document");
        add("finance", "payment", "query_accounts", "record_account_payment");
        add("suppliers", "list", "list_suppliers");
        add("procurement", "search", "search_source_purchase_orders");
        add("procurement", "record", "search_source_purchase_orders", "record_source_purchase_order", "prepare_billing_document");
        add("factory_ledger", "search", "search_factory_ledgers");
        add("factory_ledger", "detail", "search_factory_ledgers", "get_factory_ledger", "get_factory_settlement_summary");
        add("factory_ledger", "write", "search_factory_ledgers", "get_factory_ledger", "create_factory_ledger", "record_factory_ledger_transaction", "update_factory_settlement_status");
    }

    private void add(String capability, String operation, String... tools) {
        capabilities.computeIfAbsent(capability, ignored -> new LinkedHashMap<>())
                .put(operation, List.of(tools));
    }

    public String catalog(List<AgentToolDefinition> registeredTools) {
        Set<String> registered = registeredTools.stream().map(AgentToolDefinition::getName)
                .collect(java.util.stream.Collectors.toSet());
        StringBuilder text = new StringBuilder();
        capabilities.forEach((capability, operations) -> operations.forEach((operation, tools) -> {
            if (registered.containsAll(tools)) {
                text.append(capability).append('.').append(operation).append(" — ")
                        .append(String.join(", ", tools)).append('\n');
            }
        }));
        return text.toString();
    }

    public Set<String> toolsFor(List<AgentRoutingResult.Selection> selections, List<AgentToolDefinition> registeredTools) {
        Set<String> registered = registeredTools.stream().map(AgentToolDefinition::getName)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> result = new LinkedHashSet<>();
        for (AgentRoutingResult.Selection selection : selections) {
            Map<String, List<String>> operations = capabilities.get(selection.capabilityId());
            if (operations == null || !operations.containsKey(selection.operation())) {
                throw new IllegalArgumentException("路由选择了未注册的能力操作：" + selection.capabilityId() + "." + selection.operation());
            }
            List<String> tools = operations.get(selection.operation());
            if (!registered.containsAll(tools)) {
                throw new IllegalStateException("能力映射包含未注册工具：" + selection.capabilityId() + "." + selection.operation());
            }
            result.addAll(tools);
        }
        return result;
    }
}
