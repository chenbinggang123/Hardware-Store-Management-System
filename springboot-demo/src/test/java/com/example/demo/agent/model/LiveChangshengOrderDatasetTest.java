package com.example.demo.agent.model;

import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.*;
import com.example.demo.agent.service.attachment.AttachmentContentParser;
import com.example.demo.agent.service.attachment.ocr.TencentDocumentOcrClient;
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

/** Real customer-order dataset. No business writes or changes to normal configuration. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_RECEIPTS",matches="true")
class LiveChangshengOrderDatasetTest {
    @Test void recognizeUserPreparedOrders() throws Exception {
        Path project=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if(!Files.exists(project.resolve("miniprogram")))project=project.getParent();
        Path input=project.getParent().resolve("订单/创晟模具五金/十月份");
        Path output=project.resolve("tmp/changsheng-orders-20261024");Files.createDirectories(output);
        var settings=new HashMap<String,String>();
        for(String line:Files.readAllLines(project.resolve("springboot-demo/agent.local.env"),StandardCharsets.UTF_8)) {
            int at=line.indexOf('=');if(at<=0 || line.stripLeading().startsWith("#"))continue;
            String value=line.substring(at+1).trim();
            if((value.startsWith("\"")&&value.endsWith("\"")) || (value.startsWith("'")&&value.endsWith("'")))value=value.substring(1,value.length()-1);
            settings.put(line.substring(0,at).trim(),value);
        }
        var mapper=new ObjectMapper();
        var ocr=new TencentDocumentOcrClient(settings.get("TENCENT_OCR_SECRET_ID"),settings.get("TENCENT_OCR_SECRET_KEY"),settings.get("TENCENT_OCR_REGION"),60,mapper);
        var parser=new AttachmentContentParser(ocr,"ffmpeg",60);
        var properties=new AgentModelProperties();properties.setBaseUrl(settings.get("AGENT_MODEL_BASE_URL"));properties.setModel(settings.get("AGENT_MODEL_NAME"));
        properties.setApiKey(settings.get("AGENT_MODEL_API_KEY"));properties.setTimeoutSeconds(90);
        var gateway=new OpenAiCompatibleModelGateway(properties,mapper,RestClient.builder());
        var catalog=new ProductEncyclopediaCatalog();catalog.load();var knowledge=new SpecificationKnowledgeCatalog();
        var lookup=new SearchProductEncyclopediaTool(catalog,knowledge);var draft=new PrepareBillingDocumentTool(new BillingService(null,null,null,null,mapper),mapper,knowledge);
        var definitions=List.of(lookup.definition(),draft.definition());
        List<Path> files;
        try(var paths=Files.walk(input)){files=paths.filter(Files::isRegularFile).filter(p->p.toString().toLowerCase().matches(".*\\.(jpg|jpeg|png)$")).sorted().toList();}
        assertEquals(7,files.size());int succeeded=0;
        for(int index=0;index<files.size();index++) {
            Path photo=files.get(index);String sample=String.format("order-%02d",index+1);Path resultFile=output.resolve(sample+".json");
            if(Files.exists(resultFile)) {var saved=mapper.readTree(resultFile.toFile());if("DRAFT_PREVIEW_READY".equals(saved.path("status").asText()))succeeded++;continue;}
            var report=new LinkedHashMap<String,Object>();report.put("sample",sample);report.put("photo",photo.toString());report.put("model",properties.getModel());
            report.put("scope","客户拿货单；真实OCR与模型，只执行百科查询和草稿预览，不写业务数据库");
            var trace=new ArrayList<Map<String,Object>>();report.put("trace",trace);long start=System.nanoTime();
            try {
                var parsed=parser.parse(photo.getFileName().toString(),"image/jpeg",Files.readAllBytes(photo));
                report.put("ocrElapsedMs",(System.nanoTime()-start)/1_000_000);report.put("ocr",parsed);
                Files.writeString(output.resolve(sample+"-ocr.txt"),Objects.toString(parsed.text(),""),StandardCharsets.UTF_8);
                if(!"PARSED".equals(parsed.status())){report.put("status","OCR_FAILED");continue;}
                var repository=mock(AgentAttachmentRepository.class);var attachment=new AgentAttachment();
                attachment.setId(1L);attachment.setConversationId(1L);attachment.setParseStatus("PARSED");attachment.setOriginalName(photo.getFileName().toString());attachment.setExtractedText(parsed.text());
                when(repository.findByIdInAndOperatorId(List.of(1L),7L)).thenReturn(List.of(attachment));
                var service=new AgentAttachmentService(repository,mock(AgentConversationService.class),null,null,null,31457280L);
                String prompt=service.enrichPrompt("这是恒迪模具五金开给客户创晟模具五金的拿货单，请建立可编辑拿货单草稿。客户名称来自本次测试目录，票面未填写则注明来自用户提供的目录信息。保留全部商品行、日期、原单号及票面金额；模糊内容留空并标注，不能登记收款或确认保存。",List.of(1L),1L,7L);
                var context=new AgentRunContext(3000L+index,7L,prompt);report.put("measurements",context.getModelMeasurements());
                boolean eligible=BillingRecognitionProfile.select(context,definitions).isPresent();
                report.put("compactEligible",eligible);report.put("experimentStatus",eligible?"ELIGIBLE":"FULL_RULES_FALLBACK");
                
                for(int step=0;step<6;step++) {
                    var response=gateway.respond(context,definitions);
                    if(response.hasFinalOutput()){report.put("status","MODEL_DID_NOT_PREPARE_DRAFT");report.put("finalText",response.getFinalOutput());break;}
                    boolean done=false;
                    for(var call:response.getToolCalls()) {
                        trace.add(Map.of("tool",call.getName(),"arguments",call.getArguments()));
                        if("prepare_billing_document".equals(call.getName())){report.put("draft",draft.approvalPreview(new AgentToolContext(3000L+index,7L),call.getArguments()));report.put("status","DRAFT_PREVIEW_READY");done=true;succeeded++;break;}
                        if(!"search_product_encyclopedia".equals(call.getName()))throw new IllegalArgumentException("Unexpected tool");
                        Object result=lookup.execute(new AgentToolContext(3000L+index,7L),call.getArguments());context.addToolResult(call.getId(),call.getName(),call.getArguments(),result);
                    }
                    if(done)break;
                }
                if(!report.containsKey("status"))report.put("status","STEP_LIMIT");
            }catch(Exception error){report.put("status","FAILED");report.put("errorType",error.getClass().getSimpleName());if(error instanceof IllegalArgumentException)report.put("validationError",error.getMessage());}
            finally{report.put("elapsedMs",(System.nanoTime()-start)/1_000_000);mapper.writerWithDefaultPrettyPrinter().writeValue(resultFile.toFile(),report);System.out.println(sample+": "+report.get("status")+" "+report.get("elapsedMs")+"ms");}
        }
        assertEquals(7,succeeded,"Inspect per-order results for failures");
    }
}

