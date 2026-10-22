package com.example.demo.agent;

import com.example.demo.agent.harness.AgentCapabilityRegistry;
import com.example.demo.agent.harness.AgentInstructionLoader;
import com.example.demo.agent.model.AgentRoutingResult;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCapabilityRegistryTest {
    @Test
    void procurementRouteCanPrepareEditableDocumentWithoutPostingPurchase() {
        AgentCapabilityRegistry registry = new AgentCapabilityRegistry();
        List<AgentToolDefinition> tools = List.of(definition("search_source_purchase_orders"),
                definition("record_source_purchase_order"), definition("prepare_billing_document"));
        assertTrue(registry.toolsFor(
                List.of(new AgentRoutingResult.Selection("procurement", "record")), tools)
                .contains("prepare_billing_document"));
    }

    @Test
    void mapsWorkbookOperationsToOnlyTheirRegisteredTools() {
        AgentCapabilityRegistry registry = new AgentCapabilityRegistry();
        List<AgentToolDefinition> tools = List.of(
                definition("generate_excel_workbook"), definition("prepare_billing_document"), definition("read_generated_file"),
                definition("list_generated_files"), definition("search_products"));

        assertEquals(List.of("generate_excel_workbook", "prepare_billing_document"), List.copyOf(registry.toolsFor(
                List.of(new AgentRoutingResult.Selection("excel", "create")), tools)));
        assertEquals(3, registry.toolsFor(
                List.of(new AgentRoutingResult.Selection("excel", "modify_generated")), tools).size());
        assertTrue(registry.catalog(tools).contains("excel.modify_generated"));
        assertThrows(IllegalArgumentException.class, () -> registry.toolsFor(
                List.of(new AgentRoutingResult.Selection("excel", "unknown")), tools));
    }

    @Test
    void loadsAndVersionsExcelInstructionsOnlyForExcelSelections() {
        AgentInstructionLoader loader = new AgentInstructionLoader();
        AgentInstructionLoader.LoadedInstructions excel = loader.load(
                List.of(new AgentRoutingResult.Selection("excel", "create")));
        AgentInstructionLoader.LoadedInstructions inventory = loader.load(
                List.of(new AgentRoutingResult.Selection("inventory", "query")));

        assertTrue(excel.snapshot().contains("QUANTITY"));
        assertTrue(excel.versions().keySet().stream().allMatch(path -> path.contains("/excel/")));
        assertTrue(inventory.snapshot().contains("库存"));
        assertTrue(inventory.versions().keySet().stream().noneMatch(path -> path.contains("/excel/")));
    }

    private AgentToolDefinition definition(String name) {
        return new AgentToolDefinition(name, name, AgentToolRisk.R0_READ_ONLY, java.util.Map.of("type", "object"));
    }
}
