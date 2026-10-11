package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.entity.Customer;
import com.example.demo.service.CustomerService;
import com.example.demo.service.ReportService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GetDailyFinanceSummaryTool implements AgentTool {
    private final ReportService reportService;
    private final CustomerService customerService;

    public GetDailyFinanceSummaryTool(ReportService reportService, CustomerService customerService) {
        this.reportService = reportService;
        this.customerService = customerService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "get_daily_finance_summary",
                "查询某天的经营财务概况，包括销售额、已收、销售欠款、采购额和当前客户总欠款；用户说今天时可不传日期",
                AgentToolRisk.R0_READ_ONLY,
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "date", Map.of("type", "string", "description", "可选，日期 YYYY-MM-DD；不传表示今天")),
                        "required", List.of(),
                        "additionalProperties", false));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        LocalDate date = parseDate(arguments.get("date"));
        Map<String, Object> sales = reportService.getSalesReport(date, date);
        Map<String, Object> purchases = reportService.getPurchaseReport(date, date);
        Map<String, Object> daily = reportService.getDailyReport(date);

        BigDecimal salesAmount = decimalValue(sales.get("totalAmount"));
        BigDecimal receivedAmount = decimalValue(sales.get("receivedAmount"));
        BigDecimal newReceivables = salesAmount.subtract(receivedAmount).max(BigDecimal.ZERO);
        BigDecimal outstandingReceivables = customerService.getAllCustomers(null, null).stream()
                .map(Customer::getDebt)
                .map(this::zeroIfNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date);
        result.put("salesOrderCount", integerValue(sales.get("count")));
        result.put("salesAmount", salesAmount);
        result.put("receivedAmount", receivedAmount);
        result.put("newReceivables", newReceivables);
        result.put("purchaseOrderCount", integerValue(purchases.get("count")));
        result.put("purchaseAmount", decimalValue(purchases.get("totalAmount")));
        result.put("outstandingCustomerDebt", outstandingReceivables);
        result.put("inventoryWarnings", integerValue(daily.get("inventoryWarnings")));
        result.put("scopeNote", "已收和新增应收基于当日创建销售单的当前状态，不代表当日收款流水；采购额为采购单金额，不代表当日实际付款；当前系统未建立独立收付款流水，也未核算商品成本、费用和净利润");
        return result;
    }

    private LocalDate parseDate(Object value) {
        if (value == null || !StringUtils.hasText(value.toString())) return LocalDate.now();
        try {
            return LocalDate.parse(value.toString().trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("日期格式错误，请使用 YYYY-MM-DD");
        }
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (value == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    private int integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
