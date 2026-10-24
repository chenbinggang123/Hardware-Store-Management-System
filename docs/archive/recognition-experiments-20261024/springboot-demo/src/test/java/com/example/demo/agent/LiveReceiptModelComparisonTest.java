package com.example.demo.agent;

import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.model.*;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.*;
import com.example.demo.agent.tool.*;
import com.example.demo.agent.tool.builtin.*;
import com.example.demo.billing.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Paired model-only comparison: identical saved real OCR, alternate request order, no business writes. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_RECEIPTS",matches="true")
class LiveReceiptModelComparisonTest {
    @Test void compareIdenticalSavedOcr() throws Exception {
        Path project=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if(!Files.exists(project.resolve("miniprogram")))project=project.getParent();
        Path output=project.resolve("tmp/receipt-model-comparison-v4");Files.createDirectories(output);
        var settings=new HashMap<String,String>();
        for(String line:Files.readAllLines(project.resolve("springboot-demo/agent.local.env"),StandardCharsets.UTF_8)) {
            int at=line.indexOf('=');if(at<=0 || line.stripLeading().startsWith("#"))continue;
            String value=line.substring(at+1).trim();
            if((value.startsWith("\"")&&value.endsWith("\"")) || (value.startsWith("'")&&value.endsWith("'")))value=value.substring(1,value.length()-1);
            settings.put(line.substring(0,at).trim(),value);
        }
        var mapper=new ObjectMapper();var knowledge=new SpecificationKnowledgeCatalog();
        var catalog=new ProductEncyclopediaCatalog();catalog.load();var lookup=new SearchProductEncyclopediaTool(catalog,knowledge);
        var draft=new PrepareBillingDocumentTool(new BillingService(null,null,null,null,mapper),mapper,knowledge);
        var definitions=List.of(lookup.definition(),draft.definition());
        for(int photo=1;photo<=3;photo++) {
            String ocr=Files.readString(project.resolve("tmp/receipt-live-results-v4/receipt-"+photo+"-ocr.txt"),StandardCharsets.UTF_8);
            var repository=mock(AgentAttachmentRepository.class);var attachment=new AgentAttachment();
            attachment.setId(1L);attachment.setConversationId(1L);attachment.setParseStatus("PARSED");attachment.setOriginalName("receipt-"+photo+".jpg");attachment.setExtractedText(ocr);
            when(repository.findByIdInAndOperatorId(List.of(1L),7L)).thenReturn(List.of(attachment));
            var service=new AgentAttachmentService(repository,mock(AgentConversationService.class),null,null,null,31457280L);
            String prompt=service.enrichPrompt("这是我们买的采购单，请建立采购单编辑草稿。保留原单号、全部商品行和票面金额；模糊内容留空并写明疑点。",List.of(1L),1L,7L);
            // Alternate which variant is requested first to reduce ordering bias.
            for(boolean compact:photo%2==1?List.of(false,true):List.of(true,false)) {
                String variant=compact?"compact":"baseline";
                var properties=new AgentModelProperties();properties.setBaseUrl(settings.get("AGENT_MODEL_BASE_URL"));properties.setModel(settings.get("AGENT_MODEL_NAME"));
                properties.setApiKey(settings.get("AGENT_MODEL_API_KEY"));properties.setTimeoutSeconds(90);properties.setProcurementRecognitionCompact(compact);
                var gateway=new OpenAiCompatibleModelGateway(properties,mapper,RestClient.builder());var context=new AgentRunContext(2000L+photo,7L,prompt);
                var report=new LinkedHashMap<String,Object>();report.put("photo",photo);report.put("variant",variant);report.put("model",properties.getModel());
                report.put("scope","相同真实OCR文字，模型请求对照；不调用OCR，不写业务数据库");
                report.put("measurements",context.getModelMeasurements());var trace=new ArrayList<Map<String,Object>>();report.put("trace",trace);
                long start=System.nanoTime();
                try {
                    for(int step=0;step<6;step++) {
                        var response=gateway.respond(context,definitions);
                        if(response.hasFinalOutput()){report.put("status","MODEL_DID_NOT_PREPARE_DRAFT");report.put("finalText",response.getFinalOutput());break;}
                        boolean done=false;
                        for(var call:response.getToolCalls()) {
                            trace.add(Map.of("tool",call.getName(),"arguments",call.getArguments()));
                            if("prepare_billing_document".equals(call.getName())){report.put("draft",draft.approvalPreview(new AgentToolContext(2000L+photo,7L),call.getArguments()));report.put("status","DRAFT_PREVIEW_READY");done=true;break;}
                            if(!"search_product_encyclopedia".equals(call.getName()))throw new IllegalArgumentException("Unexpected tool");
                            Object result=lookup.execute(new AgentToolContext(2000L+photo,7L),call.getArguments());context.addToolResult(call.getId(),call.getName(),call.getArguments(),result);
                        }
                        if(done)break;
                    }
                    if(!report.containsKey("status"))report.put("status","STEP_LIMIT");
                }catch(Exception error){report.put("status","FAILED");report.put("errorType",error.getClass().getSimpleName());}
                finally {
                    report.put("elapsedMs",(System.nanoTime()-start)/1_000_000);
                    mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("receipt-"+photo+"-"+variant+".json").toFile(),report);
                    System.out.println("receipt-"+photo+" "+variant+": "+report.get("status")+" "+report.get("elapsedMs")+"ms");
                }
                assertEquals("DRAFT_PREVIEW_READY",report.get("status"));
            }
        }
    }
}
