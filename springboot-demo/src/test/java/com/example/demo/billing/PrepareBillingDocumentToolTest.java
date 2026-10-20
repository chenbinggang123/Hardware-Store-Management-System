package com.example.demo.billing;

import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.builtin.PrepareBillingDocumentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PrepareBillingDocumentToolTest {
    @Test void previewCannotPersistAndExecutionOnlyCreatesIdempotentUnlearnedDraft(){
        var mapper=new ObjectMapper();var billing=spy(new BillingService(null,null,null,null,mapper));var tool=new PrepareBillingDocumentTool(billing,mapper);var context=new AgentToolContext(88L,7L);
        Map<String,Object> args=Map.of("sourceNote","★原单号MOCK-1002，√单位待确认","lines",List.of(Map.of("name","√示例材料甲","specification","Φ12×100","quantity",5,"price",17,"sourceAmount","￥80.00","remark","☑ 待确认","remember",true)));
        var preview=(Map<?,?>)tool.approvalPreview(context,args);assertThat(preview.get("kind")).isEqualTo("BILLING_DRAFT");verify(billing,never()).save(any(),any(),any());
        doReturn(Map.of("id",30L,"status","DRAFT")).when(billing).save(eq(7L),isNull(),any());
        var result=(Map<?,?>)tool.execute(context,args);assertThat(result.get("editUrl")).isEqualTo("billing.html#document/30");assertThat(result.containsKey("outputId")).isFalse();
        var captor=org.mockito.ArgumentCaptor.forClass(BillingContent.class);verify(billing).save(eq(7L),isNull(),captor.capture());var c=captor.getValue();assertThat(c.getRequestKey()).isEqualTo("agent-billing-88");assertThat(c.getLines().get(0).isRemember()).isFalse();assertThat(c.getLines().get(0).getUnit()).isEmpty();assertThat(c.getLines().get(0).getAmount()).isEqualByComparingTo("85.00");assertThat(c.getLines().get(0).getName()).isEqualTo("示例材料甲");assertThat(c.getLines().get(0).getSpecification()).isEqualTo("Φ12×100");assertThat(c.getLines().get(0).getSourceAmount()).isEqualTo("￥80.00");assertThat(c.getLines().get(0).getRemark()).isEqualTo("待确认");assertThat(c.getSourceNote()).isEqualTo("原单号MOCK-1002，单位待确认");
    }
}
