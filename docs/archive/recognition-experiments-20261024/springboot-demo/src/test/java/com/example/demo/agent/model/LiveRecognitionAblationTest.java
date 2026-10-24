package com.example.demo.agent.model;

import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.*;
import com.example.demo.agent.tool.*;
import com.example.demo.agent.tool.builtin.*;
import com.example.demo.billing.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** 2x2 experiment; identical source guard and source-row instruction in all four groups. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_RECEIPTS",matches="true")
class LiveRecognitionAblationTest {
    private static final String COMMON="\n本轮所有对照组都要求：每个商品行sourceRow填写OCR第N行的N，不是票面序号；原始行引用由程序校验。日期无法识别时date明确填空字符串，不填当天。";
    private static final String FULL_OUTPUT="\n本轮输出约定以实际工具字段为准：保留rawName、rawSpecification、remark和sourceNote，疑点用中文说明，不输出工具未定义的reviewCodes。";
    private static final String COMPACT_OUTPUT="\n本轮输出约定以实际工具字段为准：不输出工具未定义的rawName、rawSpecification、remark和sourceNote，原文由sourceRow回填。疑点使用工具定义的reviewCodes，不写重复说明。";
    @Test void separateRuleCompressionFromOutputCompression() throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();if(!Files.exists(root.resolve("miniprogram")))root=root.getParent();
        Path output=root.resolve("tmp/recognition-ablation-20261024-v2");Files.createDirectories(output);
        var settings=new HashMap<String,String>();
        for(String line:Files.readAllLines(root.resolve("springboot-demo/agent.local.env"),StandardCharsets.UTF_8)){
            int at=line.indexOf('=');if(at<=0||line.stripLeading().startsWith("#"))continue;String v=line.substring(at+1).trim();
            if((v.startsWith("\"")&&v.endsWith("\""))||(v.startsWith("'")&&v.endsWith("'")))v=v.substring(1,v.length()-1);settings.put(line.substring(0,at).trim(),v);
        }
        var mapper=new ObjectMapper();var catalog=new ProductEncyclopediaCatalog();catalog.load();var knowledge=new SpecificationKnowledgeCatalog();
        var lookup=new SearchProductEncyclopediaTool(catalog,knowledge);var draft=new PrepareBillingDocumentTool(new BillingService(null,null,null,null,mapper),mapper,knowledge);
        var definitions=List.of(lookup.definition(),draft.definition());int recorded=0;
        for(String dataset:List.of("order","procurement"))for(int index=1;index<=(dataset.equals("order")?9:3);index++) {
            boolean customer=dataset.equals("order");String id=customer?String.format("order-%02d",index):"receipt-"+index;
            Path source=root.resolve(customer?"tmp/order-dataset-20261024-v1":"tmp/receipt-live-results-v4");
            String ocr=Files.readString(source.resolve(id+"-ocr.txt"),StandardCharsets.UTF_8);var previous=mapper.readTree(source.resolve(id+".json").toFile());
            var repository=mock(AgentAttachmentRepository.class);var attachment=new AgentAttachment();attachment.setId(1L);attachment.setConversationId(1L);attachment.setParseStatus("PARSED");attachment.setOriginalName(id+".jpg");attachment.setExtractedText(ocr);
            when(repository.findByIdInAndOperatorId(List.of(1L),7L)).thenReturn(List.of(attachment));
            String task=customer?"这是恒迪模具五金开给客户栉沐的拿货单，请建立可编辑拿货单草稿。客户名称来自本次测试目录，票面未填写则注明来自用户提供的目录信息。保留全部商品行、日期、原单号及票面金额；模糊内容留空并标注，不能登记收款或确认保存。":"这是我们买的采购单，请建立采购单编辑草稿。保留原单号、全部商品行和票面金额；模糊内容留空并写明疑点。";
            String prompt=new AgentAttachmentService(repository,mock(AgentConversationService.class),null,null,null,31457280L).enrichPrompt(task,List.of(1L),1L,7L);
            var initial=new AgentRunContext(1L,7L,prompt);var orderProfile=customer?CustomerOrderRecognitionExperiment.select(initial,definitions):Optional.<CustomerOrderRecognitionExperiment>empty();
            var procurementProfile=!customer?ProcurementRecognitionProfile.select(initial,definitions):Optional.<ProcurementRecognitionProfile>empty();
            boolean usable=orderProfile.isPresent()||procurementProfile.isPresent();
            List<String> variants=new ArrayList<>(List.of("full_full","short_full","full_compact","short_compact"));Collections.rotate(variants,index%4);
            for(String variant:variants){
                Path file=output.resolve(dataset+"-"+index+"-"+variant+".json");if(Files.exists(file)){recorded++;continue;}
                boolean shortRules=variant.startsWith("short"),compact=variant.endsWith("compact");
                var report=new LinkedHashMap<String,Object>();report.put("dataset",dataset);report.put("sample",index);report.put("variant",variant);report.put("photo",previous.path("photo").asText());report.put("ocrSource",source.resolve(id+"-ocr.txt").toString());
                report.put("sourceGuard","test-only v2; identical in all groups");report.put("shortRules",shortRules);report.put("compactOutput",compact);report.put("model",settings.get("AGENT_MODEL_NAME"));report.put("scope","2x2规则/输出压缩对照；相同真实OCR；不写业务数据，原方案不变");
                var context=new AgentRunContext(5000L+index,7L,prompt);report.put("measurements",context.getModelMeasurements());var trace=new ArrayList<Map<String,Object>>();report.put("rawTrace",trace);
                if(!usable){report.put("status","NO_ITEM_EVIDENCE");report.put("note","OCR缺少可用商品表头和明细；全部组相同拦截，不调用模型，不计作识别正确");report.put("elapsedMs",0);mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),report);recorded++;continue;}
                String taskRules=shortRules?(customer?orderProfile.orElseThrow().rules():procurementProfile.orElseThrow().rules()):AgentBusinessRules.TEXT;
                // Output-format instructions are an independent factor, not part of short business rules.
                String rules=taskRules.replaceAll("(?m)^只输出工具所需字段.*$","")+(compact?COMPACT_OUTPUT:FULL_OUTPUT);
                var actualRequests=new ArrayList<Map<String,Object>>();
                var builder=RestClient.builder().requestInterceptor((request,body,execution)->{
                    var tree=(ObjectNode)mapper.readTree(body);((ObjectNode)tree.path("messages").get(0)).put("content",rules+COMMON);
                    var toolArray=mapper.createArrayNode();
                    for(var original:definitions){var tool=compact?(customer?orderProfile.orElseThrow().compact(original):procurementProfile.orElseThrow().compact(original)):original;
                        var schema=(ObjectNode)mapper.valueToTree(tool.getInputSchema());
                        if("prepare_billing_document".equals(tool.getName())){
                            var items=(ObjectNode)schema.path("properties").path("lines").path("items");
                            ((ObjectNode)items.path("properties")).set("sourceRow",mapper.valueToTree(Map.of("type","integer","description","OCR第N行的N")));
                            var required=(ArrayNode)items.path("required");if(!required.toString().contains("sourceRow"))required.add("sourceRow");
                        }
                        toolArray.add(mapper.valueToTree(Map.of("type","function","function",Map.of("name",tool.getName(),"description",tool.getDescription(),"parameters",schema))));
                    }
                    tree.set("tools",toolArray);String serialized=mapper.writeValueAsString(tree);byte[] changed=serialized.getBytes(StandardCharsets.UTF_8);request.getHeaders().setContentLength(changed.length);actualRequests.add(Map.of("requestChars",serialized.length(),"systemChars",(rules+COMMON).length()));return execution.execute(request,changed);
                });
                var properties=new AgentModelProperties();properties.setBaseUrl(settings.get("AGENT_MODEL_BASE_URL"));properties.setModel(settings.get("AGENT_MODEL_NAME"));properties.setApiKey(settings.get("AGENT_MODEL_API_KEY"));properties.setTimeoutSeconds(90);properties.setProcurementRecognitionCompact(false);
                var gateway=new OpenAiCompatibleModelGateway(properties,mapper,builder);long started=System.nanoTime();
                try{
                    for(int step=0;step<6;step++){
                        var raw=gateway.respond(context,definitions);context.getModelMeasurements().get(context.getModelMeasurements().size()-1).putAll(actualRequests.get(actualRequests.size()-1));
                        if(raw.hasFinalOutput()){report.put("status","MODEL_DID_NOT_PREPARE_DRAFT");report.put("finalText",raw.getFinalOutput());break;}
                        for(var call:raw.getToolCalls())trace.add(Map.of("tool",call.getName(),"arguments",call.getArguments()));
                        var response=compact?(customer?orderProfile.orElseThrow().restore(raw):procurementProfile.orElseThrow().restore(raw)):raw;boolean done=false;
                        for(var call:response.getToolCalls()){
                            if(call.getName().equals("prepare_billing_document")){
                                var args=new LinkedHashMap<>(call.getArguments());var rawArgs=raw.getToolCalls().stream().filter(c->c.getId().equals(call.getId())).findFirst().orElseThrow().getArguments();
                                if(compact){var restoredRows=(List<Map<String,Object>>)args.get("lines");var rawRows=(List<Map<String,Object>>)rawArgs.get("lines");for(int i=0;i<restoredRows.size();i++)restoredRows.get(i).put("sourceRow",rawRows.get(i).get("sourceRow"));}
                                long guardStart=System.nanoTime();var guarded=new RecognitionSourceGuardExperiment(ocr).validate(args);report.put("guardedArguments",guarded);report.put("draft",draft.approvalPreview(new AgentToolContext(5000L+index,7L),guarded));report.put("guardElapsedMs",(System.nanoTime()-guardStart)/1_000_000);report.put("status","DRAFT_PREVIEW_READY");done=true;break;
                            }
                            if(!call.getName().equals("search_product_encyclopedia"))throw new IllegalArgumentException("Unexpected tool");Object result=lookup.execute(new AgentToolContext(5000L+index,7L),call.getArguments());context.addToolResult(call.getId(),call.getName(),call.getArguments(),result);
                        }if(done)break;
                    }if(!report.containsKey("status"))report.put("status","STEP_LIMIT");
                }catch(Exception error){report.put("status","FAILED");report.put("errorType",error.getClass().getSimpleName());if(error instanceof IllegalArgumentException)report.put("validationError",error.getMessage());}
                finally{report.put("elapsedMs",(System.nanoTime()-started)/1_000_000);mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),report);recorded++;System.out.println(dataset+"-"+index+" "+variant+": "+report.get("status")+" "+report.get("elapsedMs")+"ms");}
            }
        }assertEquals(48,recorded);
    }
}
