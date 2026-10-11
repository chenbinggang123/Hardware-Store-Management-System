package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.entity.Customer;
import com.example.demo.entity.SalesOrder;
import com.example.demo.entity.SalesOrderItem;
import com.example.demo.service.CustomerService;
import com.example.demo.service.SalesOrderService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GetCustomerSalesSummaryTool implements AgentTool {
    private static final int MAX_ORDER_DETAILS = 50;

    private final CustomerService customerService;
    private final SalesOrderService salesOrderService;

    public GetCustomerSalesSummaryTool(CustomerService customerService, SalesOrderService salesOrderService) {
        this.customerService = customerService;
        this.salesOrderService = salesOrderService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "get_customer_sales_summary",
                "按客户 ID 查询指定月份的正式销售记录和汇总；用户只给客户名称时应先调用 search_customers 确认唯一客户 ID",
                AgentToolRisk.R0_READ_ONLY,
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "customerId", Map.of("type", "integer", "description", "客户 ID"),
                                "month", Map.of("type", "string", "description", "可选，月份 YYYY-MM；不传表示本月")),
                        "required", List.of("customerId"),
                        "additionalProperties", false));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        Long customerId = longValue(arguments.get("customerId"));
        Customer customer = customerService.getCustomerById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("客户不存在"));
        YearMonth month = parseMonth(arguments.get("month"));
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        List<SalesOrder> orders = salesOrderService.getAllSalesOrders(
                customerId, null, null, start.toString(), end.toString());

        BigDecimal totalAmount = sum(orders, SalesOrder::getTotalAmount);
        BigDecimal receivedAmount = sum(orders, SalesOrder::getReceivedAmount);
        BigDecimal debtAmount = sum(orders, SalesOrder::getDebtAmount);
        List<Map<String, Object>> details = orders.stream().limit(MAX_ORDER_DETAILS).map(this::toOrderView).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customerId", customer.getId());
        result.put("customerName", safe(customer.getName()));
        result.put("month", month.toString());
        result.put("periodStart", start);
        result.put("periodEnd", end);
        result.put("orderCount", orders.size());
        result.put("salesAmount", totalAmount);
        result.put("receivedAmount", receivedAmount);
        result.put("debtAmount", debtAmount);
        result.put("currentCustomerDebt", zeroIfNull(customer.getDebt()));
        result.put("orders", details);
        result.put("detailsTruncated", orders.size() > MAX_ORDER_DETAILS);
        return result;
    }

    private Map<String, Object> toOrderView(SalesOrder order) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", order.getId());
        view.put("orderNumber", safe(order.getOrderNumber()));
        view.put("orderTime", order.getOrderTime());
        view.put("status", safe(order.getStatus()));
        view.put("payStatus", safe(order.getPayStatus()));
        view.put("totalAmount", zeroIfNull(order.getTotalAmount()));
        view.put("receivedAmount", zeroIfNull(order.getReceivedAmount()));
        view.put("debtAmount", zeroIfNull(order.getDebtAmount()));
        view.put("items", safeItems(order.getItems()).stream().map(this::toItemView).toList());
        return view;
    }

    private Map<String, Object> toItemView(SalesOrderItem item) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("productId", item.getProductId());
        view.put("productName", safe(item.getProductName()));
        view.put("quantity", item.getQuantity() == null ? 0 : item.getQuantity());
        view.put("price", zeroIfNull(item.getPrice()));
        view.put("amount", zeroIfNull(item.getAmount()));
        return view;
    }

    private BigDecimal sum(List<SalesOrder> orders, java.util.function.Function<SalesOrder, BigDecimal> mapper) {
        return orders.stream().map(mapper).map(this::zeroIfNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private YearMonth parseMonth(Object value) {
        if (value == null || !StringUtils.hasText(value.toString())) return YearMonth.now();
        try {
            return YearMonth.parse(value.toString().trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("月份格式错误，请使用 YYYY-MM");
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) throw new IllegalArgumentException("客户 ID 不能为空");
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("客户 ID 格式错误");
        }
    }

    private List<SalesOrderItem> safeItems(List<SalesOrderItem> items) {
        return items == null ? List.of() : items;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
