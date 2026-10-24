package com.example.demo.agent.model;

import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.*;
import com.example.demo.agent.tool.*;
import com.example.demo.agent.tool.builtin.*;
import com.example.demo.billing.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** All adaptations are test-only. Same saved real OCR, no business writes, no production config changes. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_RECEIPTS",matches="true")
class LiveOrderExperimentComparisonTest {
    private static final String ORDER_TASK="这是恒迪模具五金开给客户栉沐的拿货单，请建立可编辑拿货单草稿。客户名称来自本次测试目录，票面未填写则注明来自用户提供的目录信息。保留全部商品行、日期、原单号及票面金额；模糊内容留空并标注，不能登记收款或确认保存。";
    @Test void pairedCustomerOrdersAndProcurement() throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();if(!Files.exists(root.resolve("miniprogram")))root=root.getParent();
        Path output=root.resolve("tmp/order-experiment-paired-20261024-v1");Files.createDirectories(output);
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
            String ocr=Files.readString(source.resolve(id+"-ocr.txt"),StandardCharsets.UTF_8);
            var previous=mapper.readTree(source.resolve(id+".json").toFile());
            var repository=mock(AgentAttachmentRepository.class);var attachment=new AgentAttachment();attachment.setId(1L);attachment.setConversationId(1L);
            attachment.setParseStatus("PARSED");attachment.setOriginalName(id+".jpg");attachment.setExtractedText(ocr);
            when(repository.findByIdInAndOperatorId(List.of(1L),7L)).thenReturn(List.of(attachment));
            String prompt=new AgentAttachmentService(repository,mock(AgentConversationService.class),null,null,null,31457280L).enrichPrompt(customer?ORDER_TASK:"这是我们买的采购单，请建立采购单编辑草稿。保留原单号、全部商品行和票面金额；模糊内容留空并写明疑点。",List.of(1L),1L,7L);
            for(boolean experiment:index%2==1?List.of(false,true):List.of(true,false)) {
                String variant=experiment?"experiment":"baseline";Path file=output.resolve(dataset+"-"+index+"-"+variant+".json");
                if(Files.exists(file)){recorded++;continue;}
                var context=new AgentRunContext(4000L+index,7L,prompt);
                var adapter=customer&&experiment?CustomerOrderRecognitionExperiment.select(context,definitions):Optional.<CustomerOrderRecognitionExperiment>empty();
                var properties=new AgentModelProperties();properties.setBaseUrl(settings.get("AGENT_MODEL_BASE_URL"));properties.setModel(settings.get("AGENT_MODEL_NAME"));
                properties.setApiKey(settings.get("AGENT_MODEL_API_KEY"));properties.setTimeoutSeconds(90);properties.setProcurementRecognitionCompact(!customer&&experiment);
                var builder=RestClient.builder();var actualRequests=new ArrayList<Map<String,Object>>();
                if(adapter.isPresent())builder.requestInterceptor((request,body,execution)->{
                    var tree=(ObjectNode)mapper.readTree(body);((ObjectNode)tree.path("messages").get(0)).put("content",adapter.get().rules());
                    tree.set("tools",mapper.valueToTree(definitions.stream().map(adapter.get()::compact).map(t->Map.of("type","function","function",Map.of("name",t.getName(),"description",t.getDescription(),"parameters",t.getInputSchema()))).toList()));
                    String serialized=mapper.writeValueAsString(tree);byte[] changed=serialized.getBytes(StandardCharsets.UTF_8);request.getHeaders().setContentLength(changed.length);
                    actualRequests.add(Map.of("requestChars",serialized.length(),"systemChars",adapter.get().rules().length()));return execution.execute(request,changed);
                });
                var gateway=new OpenAiCompatibleModelGateway(properties,mapper,builder);var report=new LinkedHashMap<String,Object>();
                report.put("dataset",dataset);report.put("sample",index);report.put("variant",variant);report.put("model",properties.getModel());report.put("photo",previous.path("photo").asText());
                report.put("ocrSource",source.resolve(id+"-ocr.txt").toString());report.put("scope","相同真实OCR，新旧模型请求对照；不调用OCR、不写业务数据库");
                report.put("experimentApplied",experiment&&(customer?adapter.isPresent():ProcurementRecognitionProfile.select(context,definitions).isPresent()));
                report.put("fallbackReason",experiment&&customer&&adapter.isEmpty()?"没有可映射的商品表头，新方案回退原方案，仍未补回OCR漏行":"");
                var trace=new ArrayList<Map<String,Object>>();report.put("trace",trace);report.put("measurements",context.getModelMeasurements());
                long start=System.nanoTime();
                try {
                    for(int step=0;step<6;step++) {
                        var response=gateway.respond(context,definitions);
                        if(adapter.isPresent()) {
                            Map<String,Object> measurement=context.getModelMeasurements().get(context.getModelMeasurements().size()-1);measurement.putAll(actualRequests.get(actualRequests.size()-1));
                            response=adapter.get().restore(response);
                        }
                        if(response.hasFinalOutput()){report.put("status","MODEL_DID_NOT_PREPARE_DRAFT");report.put("finalText",response.getFinalOutput());break;}
                        boolean done=false;
                        for(var call:response.getToolCalls()){
                            trace.add(Map.of("tool",call.getName(),"arguments",call.getArguments()));
                            if(call.getName().equals("prepare_billing_document")) {report.put("draft",draft.approvalPreview(new AgentToolContext(4000L+index,7L),call.getArguments()));report.put("status","DRAFT_PREVIEW_READY");done=true;break;}
                            if(!call.getName().equals("search_product_encyclopedia"))throw new IllegalArgumentException("Unexpected tool");
                            Object result=lookup.execute(new AgentToolContext(4000L+index,7L),call.getArguments());context.addToolResult(call.getId(),call.getName(),call.getArguments(),result);
                        }if(done)break;
                    }if(!report.containsKey("status"))report.put("status","STEP_LIMIT");
                }catch(Exception error){report.put("status","FAILED");report.put("errorType",error.getClass().getSimpleName());if(error instanceof IllegalArgumentException)report.put("validationError",error.getMessage());}
                finally {report.put("elapsedMs",(System.nanoTime()-start)/1_000_000);mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),report);recorded++;System.out.println(dataset+"-"+index+" "+variant+": "+report.get("status")+" "+report.get("elapsedMs")+"ms");}
            }
        }assertEquals(24,recorded);
    }
}
