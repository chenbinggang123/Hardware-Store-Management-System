package com.example.demo.integration;

import com.example.demo.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportIntegrationTest {

    @Autowired
    private ReportService reportService;

    @Test
    void seedDataReportsHaveConsistentAmountsCountsAndWarnings() {
        LocalDate date = LocalDate.of(2026, 3, 27);

        Map<String, Object> purchases = reportService.getPurchaseReport(date, date);
        assertEquals(2, purchases.get("count"));
        assertEquals(new BigDecimal("7080.00"), purchases.get("totalAmount"));

        Map<String, Object> sales = reportService.getSalesReport(date, date);
        assertEquals(2, sales.get("count"));
        assertEquals(new BigDecimal("1136.00"), sales.get("totalAmount"));
        assertEquals(new BigDecimal("400.00"), sales.get("receivedAmount"));

        Map<String, Object> daily = reportService.getDailyReport(date);
        assertEquals(new BigDecimal("7080.00"), daily.get("purchase"));
        assertEquals(new BigDecimal("1136.00"), daily.get("sales"));
        assertEquals(1L, daily.get("inventoryWarnings"));

        Map<String, Object> chart = reportService.getChartReport(date, date);
        assertEquals(sales.get("totalAmount"),
                ((Map<?, ?>) chart.get("sales")).get("totalAmount"));
        assertEquals(purchases.get("totalAmount"),
                ((Map<?, ?>) chart.get("purchases")).get("totalAmount"));
    }

    @Test
    void exportCarriesTheSameRealSalesReport() {
        LocalDate date = LocalDate.of(2026, 3, 27);

        Map<String, Object> export = reportService.exportReport("sales", date, date);

        assertEquals("sales", export.get("type"));
        assertTrue(export.get("report") instanceof Map);
        Map<?, ?> report = (Map<?, ?>) export.get("report");
        assertEquals(2, report.get("count"));
        assertEquals(new BigDecimal("1136.00"), report.get("totalAmount"));
        assertTrue(export.get("data").toString().contains("1136.00"));
    }
}
