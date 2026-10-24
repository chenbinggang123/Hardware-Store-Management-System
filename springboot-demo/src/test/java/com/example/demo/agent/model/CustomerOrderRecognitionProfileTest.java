package com.example.demo.agent.model;
import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.tool.builtin.PrepareBillingDocumentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CustomerOrderRecognitionProfileTest {
    private final PrepareBillingDocumentTool tool=new PrepareBillingDocumentTool(null,new ObjectMapper());
    private AgentRunContext context(String rows){return new AgentRunContext(1L,7L,"请建立客户拿货单\n以下是用户上传附件的解析结果。\n--- 附件 ID：1；名称：order.jpg ---\n[腾讯云表格识别 V3]\n[表格 1]\n"+rows);}
    @Test void usesSelectedCustomerRulesAndLeavesSharedSchemaAlone(){
        var profile=BillingRecognitionProfile.select(context("第1行\t列1=项 目\t列2=数量\n第3行\t列1=红丹\t列2=5\n"),List.of(tool.definition())).orElseThrow();
        assertTrue(profile.rules().contains("documentType用PURCHASE"));
        assertFalse(tool.definition().getInputSchema().toString().contains("reviewCodes"));
        assertTrue(profile.compact(tool.definition()).getInputSchema().toString().contains("reviewCodes"));
        assertFalse(profile.compact(tool.definition()).getInputSchema().toString().contains("sourceAttachmentId"));
    }
    @Test void keepsOcrRowEvidenceAndFlagsWithoutChangingTradingValues(){
        var profile=BillingRecognitionProfile.select(context("第1行\t列1=项 目\n第3行\t列1=红丹\t列2=5\t列3=19\n"),List.of(tool.definition())).orElseThrow();
        var result=profile.restore(AgentModelResponse.tools(List.of(new AgentToolCall("c","prepare_billing_document",Map.of("documentType","PURCHASE","lines",List.of(Map.of("sourceRow",3,"name","红丹","quantity",5,"price",19,"reviewCodes",List.of("PRICE_UNCLEAR"))))))));
        var args=result.getToolCalls().get(0).getArguments();var row=((List<Map<String,Object>>)args.get("lines")).get(0);
        assertEquals("PURCHASE",args.get("documentType"));assertEquals(19,row.get("price"));assertEquals("红丹",row.get("rawName"));assertEquals("单价待核对",row.get("remark"));
        assertTrue(args.get("sourceNote").toString().contains("列3=19"));assertEquals(3,row.get("sourceRow"));
    }
    @Test void missingProductHeadersCauseExplicitFallback(){
        assertTrue(BillingRecognitionProfile.select(context("第9行\t列1=合计 人民币\n"),List.of(tool.definition())).isEmpty());
    }

    @Test void keepsDecimalCandidateAndSourceWhenOcrColumnsAreNoisy(){
        var profile=BillingRecognitionProfile.select(context("第1行\t列2=项目\t列3=单位\t列4=数量\t列5=单价\n第3行\t列2=大切铁20\t列3=37\t列4=80\t列5=3、2\n"),List.of(tool.definition())).orElseThrow();
        var result=profile.restore(AgentModelResponse.tools(List.of(new AgentToolCall("c","prepare_billing_document",Map.of("lines",List.of(Map.of("sourceRow",3,"name","大切铁","quantity",80,"price",3.2,"sourceAmount","256")))))));
        var args=result.getToolCalls().get(0).getArguments();
        var row=((List<Map<String,Object>>)args.get("lines")).get(0);
        assertEquals(80,row.get("quantity"));assertEquals(3.2,row.get("price"));
        assertEquals("大切铁20",row.get("rawName"));
        assertTrue(args.get("sourceNote").toString().contains("列3=37\t列4=80\t列5=3、2"));
    }

    @Test void customerFallbackRestoresShiftedNameColumnAndRejectsDuplicateReferences(){
        var profile=BillingRecognitionProfile.select(context("第1行\t列2=项目\n第3行\t列1=红丹\n"),List.of(tool.definition())).orElseThrow();
        var line=Map.<String,Object>of("sourceRow",3,"name","红丹");
        var response=profile.restore(AgentModelResponse.tools(List.of(new AgentToolCall("c","prepare_billing_document",Map.of("lines",List.of(line))))));
        assertEquals("红丹",((List<Map<String,Object>>)response.getToolCalls().get(0).getArguments().get("lines")).get(0).get("rawName"));
        // 重复引用同一行时不再整张失败，标成待核对
        var twice=profile.restore(AgentModelResponse.tools(List.of(new AgentToolCall("c","prepare_billing_document",Map.of("lines",List.of(line,line))))));
        var rows=(List<Map<String,Object>>)twice.getToolCalls().get(0).getArguments().get("lines");
        assertEquals(2,rows.size());assertTrue(rows.get(1).get("remark").toString().contains("同一行"));
    }
}
