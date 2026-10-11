package com.example.demo.agent;

import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.agent.tool.builtin.CreateFactoryLedgerTool;
import com.example.demo.agent.tool.builtin.RecordFactoryLedgerTransactionTool;
import com.example.demo.agent.tool.builtin.SearchFactoryLedgersTool;
import com.example.demo.dto.FactoryLedgerCreateRequest;
import com.example.demo.dto.FactoryLedgerTransactionRequest;
import com.example.demo.service.FactoryLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentFactoryLedgerToolsTest {
    private final AgentToolContext context = new AgentToolContext(51L, 7L);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void ledgerSearchIsReadOnlyAndPassesMonthAndKeyword() {
        FactoryLedgerService service = mock(FactoryLedgerService.class);
        when(service.list("2026-10", "宏达")).thenReturn(Map.of("ledgers", List.of()));
        SearchFactoryLedgersTool tool = new SearchFactoryLedgersTool(service);

        assertEquals(AgentToolRisk.R0_READ_ONLY, tool.definition().getRisk());
        tool.execute(context, Map.of("month", "2026-10", "keyword", "宏达"));

        verify(service).list("2026-10", "宏达");
    }

    @Test
    void ledgerTransactionRequiresApprovalAndWritesAsAgent() {
        FactoryLedgerService service = mock(FactoryLedgerService.class);
        when(service.previewTransaction(eq(3L), any())).thenReturn(Map.of(
                "factoryName", "宏达模具厂", "amount", "120.00", "balanceAfter", "620.00"));
        RecordFactoryLedgerTransactionTool tool = new RecordFactoryLedgerTransactionTool(service, objectMapper);
        Map<String, Object> arguments = Map.of(
                "ledgerId", 3,
                "type", "PURCHASE",
                "date", "2026-10-11",
                "items", List.of(Map.of(
                        "productName", "钻头",
                        "specification", "16mm",
                        "unit", "支",
                        "quantity", 10,
                        "unitPrice", 12)));

        assertEquals(AgentToolRisk.R2_WRITE, tool.definition().getRisk());
        assertEquals("宏达模具厂", cast(tool.approvalPreview(context, arguments)).get("factoryName"));
        tool.execute(context, arguments);

        ArgumentCaptor<FactoryLedgerTransactionRequest> requestCaptor = ArgumentCaptor.forClass(FactoryLedgerTransactionRequest.class);
        verify(service).addAgentTransaction(eq(3L), requestCaptor.capture(), eq(7L));
        assertEquals("PURCHASE", requestCaptor.getValue().getType());
        assertEquals("钻头", requestCaptor.getValue().getItems().get(0).getProductName());
    }

    @Test
    void createLedgerUsesCurrentOperatorAndRequiresApproval() {
        FactoryLedgerService service = mock(FactoryLedgerService.class);
        CreateFactoryLedgerTool tool = new CreateFactoryLedgerTool(service, objectMapper);
        Map<String, Object> arguments = Map.of(
                "factoryName", "宏达模具厂",
                "startDate", "2026-10-01",
                "openingBalance", 128.50);

        assertEquals(AgentToolRisk.R2_WRITE, tool.definition().getRisk());
        assertEquals("宏达模具厂", cast(tool.approvalPreview(context, arguments)).get("factoryName"));
        tool.execute(context, arguments);

        ArgumentCaptor<FactoryLedgerCreateRequest> requestCaptor = ArgumentCaptor.forClass(FactoryLedgerCreateRequest.class);
        verify(service).create(requestCaptor.capture(), eq(7L));
        assertEquals("宏达模具厂", requestCaptor.getValue().getFactoryName());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
