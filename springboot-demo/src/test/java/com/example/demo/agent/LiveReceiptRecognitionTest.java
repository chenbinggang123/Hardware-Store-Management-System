package com.example.demo.agent;

import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.model.*;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.*;
import com.example.demo.agent.service.attachment.*;
import com.example.demo.agent.service.attachment.ocr.*;
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

/** Opt-in paid live calls. Uses real photos/OCR/model, but no business persistence. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_RECEIPTS",matches="true")
class LiveReceiptRecognitionTest {
    @Test void replaySavedArgumentsWithoutNetwork() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.exists(project.resolve("miniprogram"))) project = project.getParent();
        var mapper = new ObjectMapper();
        var tool = new PrepareBillingDocumentTool(new BillingService(null,null,null,null,mapper),mapper);
        for (int index=1;index<=3;index++) {
            Path file = project.resolve("tmp/receipt-live-results/receipt-"+index+".json");
            Map<String,Object> report = mapper.readValue(file.toFile(),new com.fasterxml.jackson.core.type.TypeReference<>(){});
            var trace = (List<Map<String,Object>>)report.get("trace");
            for (var event : trace) if ("prepare_billing_document".equals(event.get("tool"))) {
                try {
                    report.put("replayedDraft",tool.approvalPreview(new AgentToolContext(1L,7L),(Map<String,Object>)event.get("arguments")));
                } catch (IllegalArgumentException error) { report.put("draftValidationError",error.getMessage()); }
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),report);
        }
    }

    @Test void runOriginalPhotosThroughRecognitionAndDraftPreview() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.exists(project.resolve("miniprogram"))) project = project.getParent();
        Path output = project.resolve("tmp/receipt-live-results-v4"); Files.createDirectories(output);
        Map<String,String> settings = new HashMap<>();
        for (String line : Files.readAllLines(project.resolve("springboot-demo/agent.local.env"),StandardCharsets.UTF_8)) {
            int equals = line.indexOf('=');
            if (equals > 0 && !line.stripLeading().startsWith("#")) {
                String value = line.substring(equals+1).trim();
                if ((value.startsWith("\"")&&value.endsWith("\"")) || (value.startsWith("'")&&value.endsWith("'"))) value=value.substring(1,value.length()-1);
                settings.put(line.substring(0,equals).trim(),value);
            }
        }
        var mapper = new ObjectMapper();
        var ocr = new TencentDocumentOcrClient(settings.get("TENCENT_OCR_SECRET_ID"),settings.get("TENCENT_OCR_SECRET_KEY"),settings.get("TENCENT_OCR_REGION"),60,mapper);
        assertTrue(ocr.isConfigured(),"Live OCR is not configured");
        var parser = new AttachmentContentParser(ocr,"ffmpeg",60);
        var properties = new AgentModelProperties();
        properties.setBaseUrl(settings.get("AGENT_MODEL_BASE_URL")); properties.setModel(settings.get("AGENT_MODEL_NAME"));
        properties.setApiKey(settings.get("AGENT_MODEL_API_KEY")); properties.setTimeoutSeconds(90);
        var gateway = new OpenAiCompatibleModelGateway(properties,mapper,RestClient.builder());
        var catalogue = new ProductEncyclopediaCatalog(); catalogue.load();
        var knowledge = new SpecificationKnowledgeCatalog();
        var lookup = new SearchProductEncyclopediaTool(catalogue,knowledge);
        var draft = new PrepareBillingDocumentTool(new BillingService(null,null,null,null,mapper),mapper,knowledge);
        var definitions = List.of(lookup.definition(),draft.definition());
        String directory = "D:/vx/xwechat_files/wxid_12bblqiphfat11_1ded/temp/RWTemp/2026-10/9dc6484b999d1b47d796795a0ed41a8c/";
        String[] files = {"2538a7ff64f60aafe94d61f814a605d6.jpg","0779b6e64f91be4c2bbffa2fbb1536da.jpg","1f355fe63cffb0fc11cecc04a83c8497.jpg"};
        for (int index=0;index<files.length;index++) {
            String sample = "receipt-"+(index+1);
            var report = new LinkedHashMap<String,Object>(); report.put("photo",directory+files[index]);
            report.put("model",properties.getModel()); report.put("scope","真实OCR与模型；仅执行百科查询和草稿预览，不写业务数据库");
            var trace = new ArrayList<Map<String,Object>>(); report.put("trace",trace);
            var modelTimes = new ArrayList<Long>(); report.put("modelRequestMs",modelTimes);
            long started = System.nanoTime();
            try {
                System.out.println(sample+": OCR started");
                var parsed = parser.parse(files[index],"image/jpeg",Files.readAllBytes(Path.of(directory+files[index])));
                report.put("ocrElapsedMs",(System.nanoTime()-started)/1_000_000);
                report.put("ocr",parsed);
                Files.writeString(output.resolve(sample+"-ocr.txt"),Objects.toString(parsed.text(),Objects.toString(parsed.error(),"")),StandardCharsets.UTF_8);
                if (!"PARSED".equals(parsed.status())) { report.put("status","OCR_FAILED"); continue; }
                System.out.println(sample+": OCR complete; model started");
                var attachments = mock(AgentAttachmentRepository.class);
                var attachment = new AgentAttachment(); attachment.setId(1L); attachment.setConversationId(1L);
                attachment.setParseStatus("PARSED"); attachment.setOriginalName(files[index]); attachment.setExtractedText(parsed.text());
                when(attachments.findByIdInAndOperatorId(List.of(1L),7L)).thenReturn(List.of(attachment));
                var service = new AgentAttachmentService(attachments,mock(AgentConversationService.class),null,parser,null,31457280L);
                long prefetchStart = System.nanoTime();
                String prompt = service.enrichPrompt("这是我们买的采购单，请建立采购单编辑草稿。保留原单号、全部商品行和票面金额；模糊内容留空并写明疑点。",List.of(1L),1L,7L);
                report.put("prefetchElapsedMs",(System.nanoTime()-prefetchStart)/1_000_000);
                report.put("prefetchedRuleIds",new ReceiptKnowledgePrefetch(knowledge).select(List.of(parsed.text())).stream().map(SpecificationKnowledgeCatalog.Rule::id).toList());
                report.put("promptChars",prompt.length());
                var context = new AgentRunContext(1000L+index,7L,prompt);
                report.put("modelMeasurements",context.getModelMeasurements());
                boolean completed = false;
                for (int step=0;step<6 && !completed;step++) {
                    long modelStart = System.nanoTime();
                    var response = gateway.respond(context,definitions);
                    modelTimes.add((System.nanoTime()-modelStart)/1_000_000);
                    if (response.hasFinalOutput()) { report.put("finalText",response.getFinalOutput()); report.put("status","MODEL_DID_NOT_PREPARE_DRAFT"); break; }
                    for (var call : response.getToolCalls()) {
                        var event = new LinkedHashMap<String,Object>(); event.put("tool",call.getName()); event.put("arguments",call.getArguments()); trace.add(event);
                        if (call.getName().equals("search_product_encyclopedia")) {
                            long lookupStart = System.nanoTime();
                            var result = lookup.execute(new AgentToolContext(1000L+index,7L),call.getArguments());
                            event.put("elapsedMs",(System.nanoTime()-lookupStart)/1_000_000);
                            event.put("result",result); context.addToolResult(call.getId(),call.getName(),call.getArguments(),result);
                        } else if (call.getName().equals("prepare_billing_document")) {
                            long draftStart = System.nanoTime();
                            var result = draft.approvalPreview(new AgentToolContext(1000L+index,7L),call.getArguments());
                            report.put("draftElapsedMs",(System.nanoTime()-draftStart)/1_000_000);
                            report.put("draft",result); report.put("status","DRAFT_PREVIEW_READY"); completed=true; break;
                        } else { throw new IllegalStateException("Unexpected tool"); }
                    }
                }
                if (!report.containsKey("status")) report.put("status","MODEL_STEP_LIMIT");
            } catch (Exception error) {
                report.put("status","LIVE_CALL_FAILED"); report.put("errorType",error.getClass().getSimpleName());
                if(error instanceof IllegalArgumentException) report.put("validationError",error.getMessage());
                // Do not dump request headers, local configuration or provider response bodies.
            } finally {
                report.put("elapsedSeconds",(System.nanoTime()-started)/1_000_000_000L);
                report.put("elapsedMs",(System.nanoTime()-started)/1_000_000);
                mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(sample+".json").toFile(),report);
                System.out.println(sample+": "+report.get("status"));
            }
        }
        assertEquals(3,Files.list(output).filter(path->path.getFileName().toString().matches("receipt-[123]\\.json")).count());
    }
}
