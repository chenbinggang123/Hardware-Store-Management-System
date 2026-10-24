package com.example.demo.agent.model;

import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.tool.builtin.PrepareBillingDocumentTool;
import com.example.demo.billing.BillingContent;
import com.example.demo.billing.BillingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class BillingRecognitionProfileTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final PrepareBillingDocumentTool tool=new PrepareBillingDocumentTool(new BillingService(null,null,null,null,mapper),mapper);
    private final String ocr="[腾讯云表格识别 V3：OCR原文，等待用户审核]\n[表格 1]\n第1行\t列1=品名及规格\t列2=数量\t列3=单价\n第3行\t列1=螺旋丝攻M6\t列2=26\t列3=3.7\n第5行\t列1=M10\t列2=1 80\t列3=45\n";
    private AgentRunContext context(String task,String data){return new AgentRunContext(1L,7L,task+"\n以下是用户上传附件的解析结果。\n--- 附件 ID：1；名称：a.jpg ---\n"+data);}
    private BillingRecognitionProfile profile(){return BillingRecognitionProfile.select(context("请建立采购单编辑草稿",ocr),List.of(tool.definition())).orElseThrow();}

    @Test void onlyUnambiguousSingleTableProcurementUsesShortRules(){
        assertTrue(profile().rules().length()<AgentBusinessRules.TEXT.length());
        for(String task:List.of("请查看附件","只保存采购照片","不要建立采购单","建立采购单并生成Excel","查询采购记录","建立采购单并收款","建立退货单","建立采购单和客户拿货单"))
            assertTrue(BillingRecognitionProfile.select(context(task,ocr),List.of(tool.definition())).isEmpty(),task);
        for(String extra:List.of("\n--- 附件 ID：2；名称：b.jpg ---\n", "\n[表格 2]\n", "\n[附件内容已截断]"))
            assertTrue(BillingRecognitionProfile.select(context("请建立采购单编辑草稿",ocr+extra),List.of(tool.definition())).isEmpty());
        assertTrue(BillingRecognitionProfile.select(context("请建立采购单编辑草稿",ocr),List.of()).isEmpty());
    }

    @Test void compactSchemaDoesNotMutateSharedToolDefinition(){
        var definition=tool.definition();
        var compact=profile().compact(definition);
        assertTrue(mapper.valueToTree(definition).toString().contains("rawSpecification"));
        assertFalse(mapper.valueToTree(compact).toString().contains("rawSpecification"));
        assertTrue(mapper.valueToTree(compact).toString().contains("sourceRow"));
        var fields=mapper.valueToTree(compact).path("inputSchema").path("properties");
        assertFalse(fields.has("remark"));assertFalse(fields.has("sourceNote"));
        assertFalse(fields.path("lines").path("items").path("properties").has("remark"));
    }

    @Test void separateSpecColumnsAndReviewCodesSurviveWithoutModelExplanations(){
        String separate="[腾讯云表格识别 V3]\n[表格 1]\n第1行\t列1=品 名\t列2=规 格\n第3行\t列1=磨棒\t列2=31-3\n第4行\t列2=36-8\n";
        var profile=BillingRecognitionProfile.select(context("建立采购单",separate),List.of(tool.definition())).orElseThrow();
        var response=AgentModelResponse.tools(List.of(new AgentToolCall("c","prepare_billing_document",Map.of("reviewCodes",List.of("DATE_UNCLEAR"),"lines",List.of(
                Map.of("sourceRow",3,"name","磨棒","reviewCodes",List.of("SPEC_UNCLEAR")),
                Map.of("sourceRow",4,"name","磨棒","reviewCodes",List.of("GROUP_UNCLEAR")))))));
        var args=profile.restore(response).getToolCalls().get(0).getArguments();
        assertFalse(args.containsKey("reviewCodes"));assertEquals("日期待核对",args.get("remark"));
        var lines=(List<Map<String,Object>>)args.get("lines");
        assertEquals("31-3",lines.get(0).get("rawSpecification"));assertEquals("规格待核对",lines.get(0).get("remark"));
        assertEquals("",lines.get(1).get("rawName"));assertEquals("36-8",lines.get(1).get("rawSpecification"));
        assertEquals("商品分组待核对；名称沿用候选，待确认",lines.get(1).get("remark"));
        assertFalse(lines.get(1).containsKey("reviewCodes"));
    }

    @Test void restoresSourceByActualOcrRowAndPreservesUncertainNumbersAndAudit(){
        var response=AgentModelResponse.tools(List.of(new AgentToolCall("c","prepare_billing_document",Map.of("documentType","PROCUREMENT","lines",List.of(
                Map.of("sourceRow",3,"name","螺旋丝攻","specification","M6","quantity",26,"price",3.7,"sourceAmount","96"),
                Map.of("sourceRow",5,"name","螺旋丝攻","specification","M10"))))));
        var args=profile().restore(response).getToolCalls().get(0).getArguments();
        var rows=(List<Map<String,Object>>)args.get("lines");
        assertEquals("螺旋丝攻M6",rows.get(0).get("rawName"));assertEquals("M6",rows.get(0).get("rawSpecification"));
        assertEquals("M10",rows.get(1).get("rawName"));assertNull(rows.get(1).get("quantity"));
        assertTrue(args.get("sourceNote").toString().contains("列2=1 80\t列3=45"));
        assertTrue(rows.get(0).get("remark").toString().contains("96.2与票面96不同"));
        assertEquals(3,rows.get(0).get("sourceRow"));
        assertEquals(1L,args.get("sourceAttachmentId"));
        var preview=(Map<?,?>)tool.approvalPreview(new com.example.demo.agent.tool.AgentToolContext(1L,7L),args);
        var content=(BillingContent)preview.get("content");assertEquals(2,content.getLines().size());
        assertEquals("M10",content.getLines().get(1).getRawSpecification());
        // 行号对不上或重复时不再整张失败，而是保留这一行并标成待核对。
        var degraded=profile().restore(AgentModelResponse.tools(List.of(new AgentToolCall("c","prepare_billing_document",Map.of("lines",List.of(
                Map.of("sourceRow",99,"name","a"),Map.of("sourceRow",3,"name","b"),Map.of("sourceRow",3,"name","c"),Map.of("name","d","reviewCodes",List.of("WHAT")))))))).getToolCalls().get(0).getArguments();
        var kept=(List<Map<String,Object>>)degraded.get("lines");
        assertEquals(4,kept.size());
        assertTrue(kept.get(0).get("remark").toString().contains("对不上原图行"));assertNull(kept.get(0).get("sourceRow"));
        assertNull(kept.get(1).get("remark"));
        assertTrue(kept.get(2).get("remark").toString().contains("同一行"));assertEquals(3,kept.get(2).get("sourceRow"));
        assertTrue(kept.get(3).get("remark").toString().contains("对不上原图行"));
    }

    @Test void photoBillingTypeChosenInEditorOverridesKeywordGuessing(){
        for(String type:List.of("PURCHASE","RETURN","PROCUREMENT")) {
            var profile=BillingRecognitionProfile.select(context("帮我开单 [拍照开单:"+type+"]",ocr),List.of(tool.definition())).orElseThrow();
            var args=profile.restore(AgentModelResponse.tools(List.of(new AgentToolCall("c","prepare_billing_document",Map.of("documentType","PURCHASE","lines",List.of(Map.of("sourceRow",3,"name","x"))))))).getToolCalls().get(0).getArguments();
            assertEquals(type,args.get("documentType"));
            assertEquals("RETURN".equals(type),profile.rules().contains("documentType用RETURN"));
        }
        // 不带标记的“帮我开单”仍然不走精准流程
        assertTrue(BillingRecognitionProfile.select(context("帮我开单",ocr),List.of(tool.definition())).isEmpty());
    }

    @Test void gatewayRestoresBothStreamingAndNormalToolCallsAndMeasuresSizes() throws Exception {
        for(boolean customer:List.of(false,true)) for(boolean stream:List.of(false,true)) {
            var request=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            String arguments=mapper.writeValueAsString(Map.of("documentType",customer?"PURCHASE":"PROCUREMENT","lines",List.of(Map.of("sourceRow",3,"name","螺旋丝攻","specification","M6"))));
            var function=Map.of("name","prepare_billing_document","arguments",arguments);
            String json=mapper.writeValueAsString(Map.of("choices",List.of(Map.of(stream?"delta":"message",Map.of("tool_calls",List.of(Map.of("index",0,"id","c","function",function)))))));
            server.createContext("/v1/chat/completions",exchange->{
                request.set(mapper.readTree(exchange.getRequestBody()));
                exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream; charset=utf-8":"application/json; charset=utf-8");
                byte[] bytes=(stream?"data: "+json+"\n\ndata: [DONE]\n\n":json).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
            try {
                var properties=new AgentModelProperties();properties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");properties.setModel("test");properties.setApiKey("test");
                var gateway=new OpenAiCompatibleModelGateway(properties,mapper,RestClient.builder());
                var context=context(customer?"请建立客户拿货单，不要登记收款":"请建立采购单编辑草稿",ocr);
                var result=stream?gateway.respondStreaming(context,List.of(tool.definition()),ignored->{}):gateway.respond(context,List.of(tool.definition()));
                assertEquals(BillingRecognitionProfile.select(context,List.of(tool.definition())).orElseThrow().rules(),request.get().path("messages").path(0).path("content").asText());
                assertTrue(request.get().path("tools").toString().contains("sourceRow"));
                assertFalse(request.get().path("tools").toString().contains("rawSpecification"));
                assertTrue(result.getToolCalls().get(0).getArguments().get("sourceNote").toString().contains("列2=1 80"));
                assertEquals(1,context.getModelMeasurements().size());
                assertEquals(stream,context.getModelMeasurements().get(0).containsKey("firstEventMs"));
                var normalContext=context("查询采购记录",ocr);
                if(stream)gateway.respondStreaming(normalContext,List.of(tool.definition()),ignored->{});
                else gateway.respond(normalContext,List.of(tool.definition()));
                assertEquals(AgentBusinessRules.TEXT,request.get().path("messages").path(0).path("content").asText());
                assertTrue(request.get().path("tools").toString().contains("rawSpecification"));
            }finally{server.stop(0);}
        }
    }
}
