package com.example.demo.agent.model;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.builtin.PrepareBillingDocumentTool;
import com.example.demo.billing.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline replay isolates the validator's effect from model generation variance. */
@EnabledIfEnvironmentVariable(named="RUN_SOURCE_GUARD_REPLAY",matches="true")
class SourceGuardReplayTest {
    @Test void replayAllSavedArgumentsWithoutAnyProviderCalls() throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();if(!Files.exists(root.resolve("miniprogram")))root=root.getParent();
        var mapper=new ObjectMapper();var tool=new PrepareBillingDocumentTool(new BillingService(null,null,null,null,mapper),mapper);
        Path source=root.resolve("tmp/order-experiment-paired-20261024-v1"),output=root.resolve("tmp/source-guard-replay-20261024-v2");Files.createDirectories(output);
        List<Path> files;try(var paths=Files.list(source)){files=paths.filter(p->p.toString().endsWith(".json")).sorted().toList();}
        for(Path file:files){
            Map<String,Object> original=mapper.readValue(file.toFile(),new TypeReference<>(){});
            Map<String,Object> replay=new LinkedHashMap<>(original);replay.put("parentResult",file.toString());replay.put("replay",true);replay.put("newModelRequests",0);
            String ocr=Files.readString(Path.of(original.get("ocrSource").toString()),StandardCharsets.UTF_8);
            List<Map<String,Object>> trace=(List<Map<String,Object>>)original.get("trace");
            var args=trace.stream().filter(e->"prepare_billing_document".equals(e.get("tool"))).map(e->(Map<String,Object>)e.get("arguments")).findFirst().orElseThrow();
            replay.remove("draft");long started=System.nanoTime();
            try{
                var guarded=new RecognitionSourceGuardExperiment(ocr).validate(args);replay.put("guardedArguments",guarded);
                replay.put("draft",tool.approvalPreview(new AgentToolContext(1L,7L),guarded));replay.put("status","DRAFT_PREVIEW_READY");
            }catch(IllegalArgumentException error){replay.put("status","NO_VERIFIABLE_DRAFT");replay.put("guardError",error.getMessage());}
            replay.put("guardElapsedMs",(System.nanoTime()-started)/1_000_000);
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(file.getFileName()).toFile(),replay);
        }
        assertEquals(24,files.size());
    }
}
