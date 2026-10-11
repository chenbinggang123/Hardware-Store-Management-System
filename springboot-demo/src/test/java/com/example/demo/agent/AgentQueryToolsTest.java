package com.example.demo.agent;

import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.agent.tool.builtin.GetCustomerSalesSummaryTool;
import com.example.demo.agent.tool.builtin.GetDailyFinanceSummaryTool;
import com.example.demo.agent.tool.builtin.ListSuppliersTool;
import com.example.demo.entity.Customer;
import com.example.demo.entity.SalesOrder;
import com.example.demo.entity.SalesOrderItem;
import com.example.demo.entity.Supplier;
import com.example.demo.service.CustomerService;
import com.example.demo.service.ReportService;
import com.example.demo.service.SalesOrderService;
import com.example.demo.service.SupplierService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentQueryToolsTest {
    private final AgentToolContext context = new AgentToolContext(60L, 7L);

    @Test
    void listsAllSuppliersWithoutForcingAKeyword() {
        SupplierService supplierService = mock(SupplierService.class);
        Supplier first = supplier(1L, "锐恒五金源头厂", "张师傅", "13800138000");
        Supplier second = supplier(2L, "远东电线", "李经理", "02112345678");
        when(supplierService.getAllSuppliers("")).thenReturn(List.of(first, second));
        ListSuppliersTool tool = new ListSuppliersTool(supplierService);

        Map<String, Object> result = cast(tool.execute(context, Map.of()));

        assertEquals(AgentToolRisk.R0_READ_ONLY, tool.definition().getRisk());
        assertEquals(2, result.get("count"));
        List<?> suppliers = (List<?>) result.get("suppliers");
        assertEquals("锐恒五金源头厂", cast(suppliers.get(0)).get("name"));
        assertEquals("138****8000", cast(suppliers.get(0)).get("phone"));
        verify(supplierService).getAllSuppliers("");
    }

    @Test
    void dailyFinanceSummaryUsesBusinessReportsAndExplainsItsScope() {
        ReportService reportService = mock(ReportService.class);
        CustomerService customerService = mock(CustomerService.class);
        LocalDate date = LocalDate.of(2026, 10, 7);
        when(reportService.getSalesReport(date, date)).thenReturn(Map.of(
                "count", 2, "totalAmount", new BigDecimal("500.00"),
                "receivedAmount", new BigDecimal("320.00")));
        when(reportService.getPurchaseReport(date, date)).thenReturn(Map.of(
                "count", 1, "totalAmount", new BigDecimal("120.00")));
        when(reportService.getDailyReport(date)).thenReturn(Map.of("inventoryWarnings", 3L));
        Customer a = customer(1L, "A客户", "40.00");
        Customer b = customer(2L, "B客户", "60.00");
        when(customerService.getAllCustomers(null, null)).thenReturn(List.of(a, b));
        GetDailyFinanceSummaryTool tool = new GetDailyFinanceSummaryTool(reportService, customerService);

        Map<String, Object> result = cast(tool.execute(context, Map.of("date", "2026-10-07")));

        assertEquals(new BigDecimal("500.00"), result.get("salesAmount"));
        assertEquals(new BigDecimal("320.00"), result.get("receivedAmount"));
        assertEquals(new BigDecimal("180.00"), result.get("newReceivables"));
        assertEquals(new BigDecimal("100.00"), result.get("outstandingCustomerDebt"));
        assertEquals(3, result.get("inventoryWarnings"));
        assertTrue(result.get("scopeNote").toString().contains("未核算"));
    }

    @Test
    void customerMonthlySalesReturnsSummaryAndOrderDetails() {
        CustomerService customerService = mock(CustomerService.class);
        SalesOrderService salesOrderService = mock(SalesOrderService.class);
        Customer customer = customer(9L, "A顾客", "75.00");
        when(customerService.getCustomerById(9L)).thenReturn(Optional.of(customer));
        SalesOrder order = salesOrder();
        when(salesOrderService.getAllSalesOrders(
                9L, null, null, "2026-10-01", "2026-10-31")).thenReturn(List.of(order));
        GetCustomerSalesSummaryTool tool = new GetCustomerSalesSummaryTool(customerService, salesOrderService);

        Map<String, Object> result = cast(tool.execute(context, Map.of(
                "customerId", 9, "month", "2026-10")));

        assertEquals("A顾客", result.get("customerName"));
        assertEquals(1, result.get("orderCount"));
        assertEquals(new BigDecimal("120.00"), result.get("salesAmount"));
        assertEquals(new BigDecimal("80.00"), result.get("receivedAmount"));
        assertEquals(new BigDecimal("40.00"), result.get("debtAmount"));
        List<?> orders = (List<?>) result.get("orders");
        assertEquals("SO20261007001", cast(orders.get(0)).get("orderNumber"));
        assertEquals(1, ((List<?>) cast(orders.get(0)).get("items")).size());
        verify(salesOrderService).getAllSalesOrders(
                9L, null, null, "2026-10-01", "2026-10-31");
    }

    @Test
    void rejectsInvalidMonthInsteadOfGuessing() {
        CustomerService customerService = mock(CustomerService.class);
        SalesOrderService salesOrderService = mock(SalesOrderService.class);
        when(customerService.getCustomerById(9L)).thenReturn(Optional.of(customer(9L, "A顾客", "0")));
        GetCustomerSalesSummaryTool tool = new GetCustomerSalesSummaryTool(customerService, salesOrderService);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tool.execute(context, Map.of("customerId", 9, "month", "十月")));

        assertEquals("月份格式错误，请使用 YYYY-MM", exception.getMessage());
    }

    private Supplier supplier(Long id, String name, String contact, String phone) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setName(name);
        supplier.setContact(contact);
        supplier.setPhone(phone);
        return supplier;
    }

    private Customer customer(Long id, String name, String debt) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setName(name);
        customer.setDebt(new BigDecimal(debt));
        return customer;
    }

    private SalesOrder salesOrder() {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductId(11L);
        item.setProductName("活动扳手");
        item.setQuantity(2);
        item.setPrice(new BigDecimal("60.00"));
        item.setAmount(new BigDecimal("120.00"));

        SalesOrder order = new SalesOrder();
        order.setId(21L);
        order.setOrderNumber("SO20261007001");
        order.setOrderTime(LocalDateTime.of(2026, 10, 7, 9, 30));
        order.setStatus("已出库");
        order.setPayStatus("部分");
        order.setTotalAmount(new BigDecimal("120.00"));
        order.setReceivedAmount(new BigDecimal("80.00"));
        order.setDebtAmount(new BigDecimal("40.00"));
        order.setItems(List.of(item));
        return order;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
