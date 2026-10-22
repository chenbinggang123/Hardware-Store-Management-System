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

        billingLedger.reopen(OWNER, documentId);
        assertThat(balance(ledgerId)).isEqualByComparingTo("0.00");
        Map<String, Object> reopened = billing.get(OWNER, documentId);
        BillingContent changed = (BillingContent) reopened.get("content");
        changed.getLines().get(0).setQuantity(new BigDecimal("50"));
        changed.setVersion((Long) reopened.get("version"));
        reopened = billing.save(OWNER, documentId, changed);
        billing.confirm(OWNER, documentId, (Long) reopened.get("version"));
        Map<String, Object> reposted = billingLedger.post(OWNER, documentId, null);
        assertThat(reposted.get("reversed")).isEqualTo(false);
        assertThat(balance(ledgerId)).isEqualByComparingTo("132.00");
        assertThat(confirmed.get("number")).isNotNull();
    }

    private BigDecimal balance(Long ledgerId) {
        Map<?, ?> summary = (Map<?, ?>) factoryLedgers.get(ledgerId, null).get("summary");
        return new BigDecimal(String.valueOf(summary.get("balanceAmount")));
    }
}
