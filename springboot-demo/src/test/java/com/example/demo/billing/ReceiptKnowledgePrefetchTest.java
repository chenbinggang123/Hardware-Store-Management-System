package com.example.demo.billing;

import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.builtin.PrepareBillingDocumentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReceiptKnowledgePrefetchTest {
    @Test void deduplicatesItemCategoriesAndIgnoresPrintedSupplierAdvertising() {
        var prefetch = new ReceiptKnowledgePrefetch(new SpecificationKnowledgeCatalog());
        String text = "[上下文] 专营：钻头、砂纸、丝锥、量具\n第3行\t列1=镀钛钻头中1\n第4行\t列1=镀钛钻头P5.5\n第5行\t列1=合金铰刀巾16";
        var result = prefetch.select(List.of(text,text));
        assertThat(result).extracting(SpecificationKnowledgeCatalog.Rule::id).containsExactly("drill-diameter","reamer-diameter");
        assertThat(prefetch.prompt(List.of(text))).contains("无需再次调用百科").doesNotContain("P120");
        assertThat(prefetch.select(List.of("[上下文] 专营钻头"))).isEmpty();
    }

    @Test void longAuditNotesMoveToSourceNoteWithoutLosingEvidence() {
        var mapper = new ObjectMapper();
        var tool = new PrepareBillingDocumentTool(new BillingService(null,null,null,null,mapper),mapper);
        String documentAudit = "整单疑点".repeat(60);
        String rowAudit = "行内疑点".repeat(40);
        var preview = (Map<?,?>)tool.approvalPreview(new AgentToolContext(1L,7L),Map.of("remark",documentAudit,"sourceNote","原始来源", "lines",List.of(Map.of("name","钻头","remark",rowAudit,"quantity",100,"price",4.5))));
        var content = (BillingContent)preview.get("content");
        assertThat(content.getRemark().length()).isLessThanOrEqualTo(200);
        assertThat(content.getLines().get(0).getRemark().length()).isLessThanOrEqualTo(120);
        assertThat(content.getSourceNote()).contains(documentAudit,rowAudit,"原始来源");
        assertThat(content.getLines().get(0).getAmount()).isEqualByComparingTo("450.00");
    }
}
