package com.example.demo.integration;

import com.example.demo.agent.harness.AgentHarness;
import com.example.demo.agent.harness.AgentRunStatus;
import com.example.demo.agent.model.AgentModelGateway;
import com.example.demo.agent.model.AgentModelResponse;
import com.example.demo.agent.model.AgentToolCall;
import com.example.demo.billing.BillingContent;
import com.example.demo.billing.BillingLedgerService;
import com.example.demo.billing.BillingService;
import com.example.demo.dto.FactoryLedgerCreateRequest;
import com.example.demo.service.FactoryLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 小五生成开单草稿 → 开单记录能看到 → 保存记账 → 修改 → 重新记账，整条链路跑在测试 MySQL 上。 */
@SpringBootTest
@ActiveProfiles("test")
class BillingFlowIntegrationTest {
    private static final long OWNER = 1L;

    @Autowired private AgentHarness harness;
    @Autowired private BillingService billing;
    @Autowired private BillingLedgerService billingLedger;
    @Autowired private FactoryLedgerService factoryLedgers;
    @Autowired private com.example.demo.billing.BillingDocumentRepository documents;
    @Autowired private com.example.demo.agent.tool.builtin.QueryProcurementReceiptsTool receiptQuery;
    @Autowired private com.example.demo.agent.tool.AgentToolRegistry toolRegistry;

    @Test
    void runtimeToolsFollowCurrentWorkflow() {
        var names=toolRegistry.definitions().stream().map(com.example.demo.agent.tool.AgentToolDefinition::getName).toList();
        assertThat(names).contains("query_procurement_receipts","record_ledger_payment","read_monthly_summary","read_billing_documents");
        assertThat(names).doesNotContain("record_factory_ledger_transaction","get_daily_finance_summary","get_customer_sales_summary","update_factory_settlement_status","create_sales_draft");
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnDocumentReducesBalanceAndDeletedDocumentDisappearsFromBook() {
        FactoryLedgerCreateRequest request=new FactoryLedgerCreateRequest();
        request.setFactoryName("退货测试"+UUID.randomUUID());
        Long ledgerId=((Number)factoryLedgers.create(request,OWNER).get("id")).longValue();
        BillingContent content=new BillingContent();content.setDocumentType("RETURN");content.setLedgerId(ledgerId);
        content.setDate(java.time.LocalDate.now().toString());
        BillingContent.Line line=new BillingContent.Line();line.setName("螺丝");line.setUnit("个");line.setQuantity(new BigDecimal("2"));line.setPrice(new BigDecimal("5"));
        content.setLines(List.of(line));
        Map<String,Object> draft=billing.save(OWNER,null,content);
        Long id=((Number)draft.get("id")).longValue();
        billing.confirm(OWNER,id,(Long)draft.get("version"));
        billingLedger.post(OWNER,id,ledgerId);
        assertThat(balance(ledgerId)).isEqualByComparingTo("-10.00");
        List<Map<String,Object>> entries=(List<Map<String,Object>>)factoryLedgers.get(ledgerId,null).get("transactions");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("type")).isEqualTo("RETURN");
        assertThat(entries.get(0).get("documentId")).isEqualTo(id);
        billingLedger.deleteFromLedger(OWNER,ledgerId,((Number)entries.get(0).get("id")).longValue());
        assertThat(balance(ledgerId)).isEqualByComparingTo("0.00");
        assertThat((List<?>)factoryLedgers.get(ledgerId,null).get("transactions")).isEmpty();
    }

    @Test
    void filtersAndBatchDraftCleanupUseTheWholeDatabaseResult() {
        String customer = "筛选测试" + UUID.randomUUID().toString().substring(0, 8);
        List<Long> ids = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                BillingContent content = new BillingContent();
                content.setRequestKey(UUID.randomUUID().toString());
                content.setCustomerName(customer);
                content.setDate(java.time.LocalDate.now().toString());
                BillingContent.Line line = new BillingContent.Line();
                line.setName("筛选商品"); line.setUnit("个");
                line.setQuantity(BigDecimal.ONE); line.setPrice(BigDecimal.ONE);
                content.setLines(List.of(line));
                Map<String, Object> draft = billing.save(OWNER, null, content);
                Long id = (Long) draft.get("id");
                ids.add(id);
                if (i > 0) billing.confirm(OWNER, id, (Long) draft.get("version"));
                if (i == 2) billingLedger.voidDocument(OWNER, id, "筛选测试");
            }
            for (String status : List.of("DRAFT", "CONFIRMED", "VOIDED")) {
                Map<String, Object> filtered = billingLedger.records(OWNER, customer, status, 0, 1);
                assertThat(((Number) filtered.get("total")).longValue()).isEqualTo(1);
                assertThat(filtered.get("items")).asList().hasSize(1);
            }
            assertThat(((Number) billingLedger.records(OWNER, customer, "", 0, 1).get("total")).longValue()).isEqualTo(3);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> billingLedger.deleteDrafts(OWNER, ids))
                    .hasMessageContaining("本次没有删除");
            assertThat(billing.get(OWNER, ids.get(0)).get("status")).isEqualTo("DRAFT");
            assertThat(billingLedger.deleteDrafts(OWNER, List.of(ids.get(0)))).isEqualTo(1);
            assertThat(((Number) billingLedger.records(OWNER, customer, "DRAFT", 0, 20).get("total")).longValue()).isZero();
            assertThat(billing.get(OWNER, ids.get(1)).get("status")).isEqualTo("CONFIRMED");
            assertThat(billing.get(OWNER, ids.get(2)).get("status")).isEqualTo("VOIDED");
        } finally {
            documents.deleteAllById(ids);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentDraftShowsUpInRecordsAndCanBePostedEditedAndReposted() {
        String customer = "集成测试厂" + UUID.randomUUID().toString().substring(0, 6);
        AgentModelGateway gateway = (context, tools) -> context.getToolResults().isEmpty()
                ? AgentModelResponse.tools(List.of(new AgentToolCall("call-1", "prepare_billing_document", Map.of(
                        "customerName", customer,
                        "lines", List.of(Map.of("name", "内六角螺丝", "unit", "个", "quantity", 40, "price", 2.64))))))
                : AgentModelResponse.finalAnswer("草稿已准备好");

        var run = harness.run("帮我把这张单生成开单预览", OWNER, gateway);

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.getOutput()).contains("BILLING_DOCUMENT");
        Matcher matcher = Pattern.compile("\"documentId\"\\s*:\\s*(\\d+)").matcher(run.getOutput());
        assertThat(matcher.find()).isTrue();
        Long documentId = Long.valueOf(matcher.group(1));

        Map<String, Object> records = billingLedger.records(OWNER, customer, 0, 20);
        List<Map<String, Object>> items = (List<Map<String, Object>>) records.get("items");
        assertThat(items).extracting(row -> row.get("id")).contains(documentId);
        assertThat(billingLedger.records(OWNER, "内六角", 0, 20).get("items")).asList().isNotEmpty();

        FactoryLedgerCreateRequest ledgerRequest = new FactoryLedgerCreateRequest();
        ledgerRequest.setFactoryName(customer);
        Long ledgerId = ((Number) factoryLedgers.create(ledgerRequest, OWNER).get("id")).longValue();

        Map<String, Object> draft = billing.get(OWNER, documentId);
        BillingContent content = (BillingContent) draft.get("content");
        content.setLedgerId(ledgerId);
        content.setVersion((Long) draft.get("version"));
        draft = billing.save(OWNER, documentId, content);
        Map<String, Object> confirmed = billing.confirm(OWNER, documentId, (Long) draft.get("version"));
        assertThat(billingLedger.post(OWNER, documentId, null).get("posted")).isEqualTo(true);
        assertThat(balance(ledgerId)).isEqualByComparingTo("105.60");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> billingLedger.deleteDocument(OWNER, documentId)).hasMessageContaining("账本中处理");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> billingLedger.reopen(OWNER, documentId)).hasMessageContaining("账本中处理");
        Map<String, Object> managed = billingLedger.records(OWNER, customer, 0, 20);
        assertThat(((List<Map<String,Object>>)managed.get("items")).get(0).get("ledgerManaged")).isEqualTo(true);
        Map<String,Object> book = factoryLedgers.get(ledgerId,null);
        List<Map<String,Object>> entries = (List<Map<String,Object>>)book.get("transactions");
        Long transactionId = ((Number)entries.get(0).get("id")).longValue();
        billingLedger.deleteFromLedger(OWNER,ledgerId,transactionId);
        assertThat(balance(ledgerId)).isEqualByComparingTo("0.00");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> billing.get(OWNER,documentId)).hasMessageContaining("单据不存在");
        assertThat(confirmed.get("number")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void procurementGoesIntoSupplierLedgerSeparateFromCustomerLedger() {
        String factory = "进货测试厂" + UUID.randomUUID().toString().substring(0, 6);
        // 同一家厂既买我们的货、又卖货给我们：“买的”和“进货的”各有一本账。
        FactoryLedgerCreateRequest customerRequest = new FactoryLedgerCreateRequest();
        customerRequest.setFactoryName(factory);
        Long customerLedgerId = ((Number) factoryLedgers.create(customerRequest, OWNER).get("id")).longValue();

        BillingContent content = new BillingContent();
        content.setDocumentType("PROCUREMENT");
        content.setCustomerName(factory);
        content.setSourceDocumentNumber("YD-" + UUID.randomUUID().toString().substring(0, 8));
        content.setDate(java.time.LocalDate.now().toString());
        BillingContent.Line line = new BillingContent.Line();
        line.setName("进货测试螺母" + UUID.randomUUID().toString().substring(0, 4)); line.setUnit("个");
        line.setQuantity(new BigDecimal("10")); line.setPrice(new BigDecimal("1.5"));
        content.setLines(List.of(line));
        Map<String, Object> draft = billing.save(OWNER, null, content);
        Long id = ((Number) draft.get("id")).longValue();
        billing.confirm(OWNER, id, (Long) draft.get("version"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> billingLedger.post(OWNER, id, customerLedgerId))
                .hasMessageContaining("进货的");
        Map<String, Object> posting = billingLedger.post(OWNER, id, null);
        Map<?,?> received=(Map<?,?>)receiptQuery.execute(new com.example.demo.agent.tool.AgentToolContext(1L,OWNER),Map.of("date",java.time.LocalDate.now().toString()));
        List<Map<?,?>> receivedItems=new java.util.ArrayList<>((List<Map<?,?>>)received.get("items"));
        for(int page=1;page<((Number)received.get("totalPages")).intValue();page++){
            Map<?,?> next=(Map<?,?>)receiptQuery.execute(new com.example.demo.agent.tool.AgentToolContext(1L,OWNER),Map.of("date",java.time.LocalDate.now().toString(),"page",page));
            receivedItems.addAll((List<Map<?,?>>)next.get("items"));
        }
        assertThat(receivedItems).anySatisfy(row->{
            assertThat(row.get("name")).isEqualTo(line.getName());
            assertThat(row.get("receivedQuantity")).isEqualTo(10L);
            assertThat(row.get("documentIds")).asList().contains(id);
        });
        assertThat(posting.get("posted")).isEqualTo(true);
        Long supplierLedgerId = ((Number) posting.get("ledgerId")).longValue();
        assertThat(supplierLedgerId).isNotEqualTo(customerLedgerId);
        assertThat(factoryLedgers.kindOf(supplierLedgerId)).isEqualTo(FactoryLedgerService.KIND_SUPPLIER);
        assertThat(balance(supplierLedgerId)).isEqualByComparingTo("15.00");
        assertThat(balance(customerLedgerId)).isEqualByComparingTo("0.00");

        Map<String, Object> book = factoryLedgers.get(supplierLedgerId, null);
        assertThat(book.get("kindLabel")).isEqualTo("进货的");
        List<Map<String, Object>> entries = (List<Map<String, Object>>) book.get("transactions");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("typeLabel")).isEqualTo("进货");

        List<Map<String, Object>> suppliers = (List<Map<String, Object>>) factoryLedgers.list(null, factory, "SUPPLIER").get("ledgers");
        List<Map<String, Object>> customers = (List<Map<String, Object>>) factoryLedgers.list(null, factory, "CUSTOMER").get("ledgers");
        assertThat(suppliers).extracting(row -> row.get("id")).containsExactly(supplierLedgerId);
        assertThat(customers).extracting(row -> row.get("id")).containsExactly(customerLedgerId);

        // 拿货单不能记进“进货的”账本
        BillingContent sale = new BillingContent();
        sale.setDate(java.time.LocalDate.now().toString());
        BillingContent.Line saleLine = new BillingContent.Line();
        saleLine.setName("导柱"); saleLine.setUnit("个"); saleLine.setQuantity(BigDecimal.ONE); saleLine.setPrice(BigDecimal.TEN);
        sale.setLines(List.of(saleLine));
        Map<String, Object> saleDraft = billing.save(OWNER, null, sale);
        Long saleId = ((Number) saleDraft.get("id")).longValue();
        billing.confirm(OWNER, saleId, (Long) saleDraft.get("version"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> billingLedger.post(OWNER, saleId, supplierLedgerId))
                .hasMessageContaining("买的");

        // 从进货账本删掉这张采购单：应付归零
        billingLedger.deleteFromLedger(OWNER, supplierLedgerId, ((Number) entries.get(0).get("id")).longValue());
        assertThat(balance(supplierLedgerId)).isEqualByComparingTo("0.00");
        billingLedger.deleteDocument(OWNER, saleId);
        removeTestProducts("进货测试螺母%");
    }

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 采购单会新建商品和库存，测试库不回滚，用完删掉，免得影响库存预警类的统计。 */
    private void removeTestProducts(String namePattern) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM product WHERE name LIKE ?", Long.class, namePattern);
        for (Long productId : ids) {
            for (String table : List.of("inventory_log", "inventory", "product_price_history", "billing_product_memory", "billing_product_usage")) {
                try { jdbc.update("DELETE FROM " + table + " WHERE product_id = ?", productId); }
                catch (org.springframework.dao.DataAccessException ignored) { /* 表里没有 product_id 列 */ }
            }
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
    }

    private BigDecimal balance(Long ledgerId) {
        Map<?, ?> summary = (Map<?, ?>) factoryLedgers.get(ledgerId, null).get("summary");
        return new BigDecimal(String.valueOf(summary.get("balanceAmount")));
    }
}
