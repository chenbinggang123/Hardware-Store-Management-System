package com.example.demo.agent;

import com.example.demo.agent.harness.*;
import com.example.demo.agent.model.AgentBusinessRules;
import com.example.demo.agent.model.AgentRoutingResult;
import com.example.demo.agent.policy.*;
import com.example.demo.agent.tool.*;
import com.example.demo.agent.tool.builtin.*;
import com.example.demo.billing.*;
import com.example.demo.entity.*;
import com.example.demo.service.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentWorkflowContractTest {
    @Test void threeReceiptSamplesKeepBuyingDirectionUncertaintyAndIndependentDrafts() throws Exception {
        var mapper = new ObjectMapper();
        var service = spy(new BillingService(null,null,null,null,mapper));
        var tool = new PrepareBillingDocumentTool(service, mapper);
        var context = new AgentToolContext(88L,7L);
        List<Map<String,Object>> cases;
        try (var input = getClass().getResourceAsStream("/agent/receipt-regressions.json")) {
            cases = mapper.readValue(input, new TypeReference<>() {});
        }
        assertThat(cases).hasSize(3);
        var numbers = new HashSet<String>();
        for (var args : cases) {
            assertThat(new AgentPolicyEngine().evaluate(context, tool, args)).isEqualTo(AgentPolicyDecision.ALLOW);
            var preview = (Map<?,?>) tool.approvalPreview(context, args);
            var content = (BillingContent) preview.get("content");
            assertThat(content.getDocumentType()).isEqualTo("PROCUREMENT");
            assertThat(content.getCustomerName()).isEqualTo(args.get("customerName"));
            assertThat(content.getSourceNote()).isEqualTo(args.get("sourceNote"));
            assertThat(content.getLines()).hasSize(((List<?>)args.get("lines")).size());
            assertThat(content.getLines().get(1).getName()).isEqualTo(content.getLines().get(0).getName());
            assertThat(content.getLines().get(1).getRemark()).contains("待确认");
            assertThat(content.getLines()).allMatch(line -> !line.isRemember());
            numbers.add(content.getSourceDocumentNumber());
        }
        assertThat(numbers).containsExactlyInAnyOrder("0002050","0000855","0000863");
        verify(service, never()).save(any(), any(), any());
    }

    @Test void productWithoutBarcodeReachesServiceAndCreatesZeroInventory() {
        var products = mock(ProductService.class); var inventories = mock(InventoryService.class);
        when(products.saveProduct(any(), eq(7L))).thenAnswer(call -> {
            Product product = call.getArgument(0);
            assertThat(product.getBarcode()).isNull();
            product.setBarcode("ZD-INTERNAL-TEST"); product.setId(1L); return product;
        });
        when(inventories.saveInventory(any())).thenAnswer(call -> { Inventory inventory = call.getArgument(0); inventory.setId(2L); return inventory; });
        var tool = new CreateProductTool(products, inventories);
        assertThat(tool.definition().getInputSchema().get("required")).isEqualTo(List.of("name"));
        var result = (Map<?,?>) tool.execute(new AgentToolContext(1L,7L), Map.of("name","磨棒","spec","3×1-3","unit","支"));
        assertThat(result.get("barcode")).isEqualTo("ZD-INTERNAL-TEST");
        assertThat(result.get("stock")).isEqualTo(0);
    }

    @Test void currentDocumentRoutesCanMatchProductsButCannotPostLegacyPurchases() {
        var registry = new AgentCapabilityRegistry();
        var definitions = List.of("search_products","search_product_encyclopedia","prepare_billing_document","read_billing_documents","create_product")
                .stream().map(name -> new AgentToolDefinition(name,name,AgentToolRisk.R0_READ_ONLY,Map.of())).toList();
        var tools = registry.toolsFor(List.of(new AgentRoutingResult.Selection("procurement","record")),definitions);
        assertThat(tools).contains("search_products","search_product_encyclopedia","prepare_billing_document")
                .doesNotContain("record_source_purchase_order","commit_sales_draft","create_product");
        assertThat(registry.toolsFor(List.of(new AgentRoutingResult.Selection("billing","read")),definitions)).containsExactly("read_billing_documents");
        assertThat(registry.toolsFor(List.of(new AgentRoutingResult.Selection("products","create")),definitions)).contains("search_products","create_product");
        var instructions = new AgentInstructionLoader().load(List.of(new AgentRoutingResult.Selection("billing","prepare")));
        assertThat(instructions.versions()).containsKeys("agent/business-rules.txt","agent/skills/billing/SKILL.md");
        assertThat(AgentBusinessRules.TEXT).contains("条码可选","草稿不入库","续行不限两行")
                .doesNotContain("receiveStock 必须为 false","新增商品至少需要明确商品名称和唯一条码","正式提交还需要第二次用户确认");
    }
}
