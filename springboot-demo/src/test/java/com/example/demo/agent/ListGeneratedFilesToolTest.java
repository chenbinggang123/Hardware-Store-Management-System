package com.example.demo.agent;

import com.example.demo.agent.dto.AgentFileView;
import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.agent.tool.builtin.ListGeneratedFilesTool;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListGeneratedFilesToolTest {

    @Test
    void listsRealFilesWithStatusTextAndGuidance() {
        AgentOutputService service = mock(AgentOutputService.class);
        when(service.listMine(2L)).thenReturn(List.of(
                new AgentFileView(7L, "复测.xlsx", "x", 100, "READY", AgentOutputService.AGENT_SOURCE_TYPE, 58L, 16L, null,
                        LocalDateTime.of(2026, 10, 13, 17, 29), null, "/agent/outputs/7/download", null, Map.of("rowCount", 2)),
                new AgentFileView(2L, "收据.xlsx", "x", 100, AgentOutputService.MISSING_STATUS, AgentOutputService.AGENT_SOURCE_TYPE,
                        51L, 9L, null, LocalDateTime.of(2026, 10, 12, 8, 16), null, "/agent/outputs/2/download",
                        AgentOutputService.MISSING_MESSAGE, Map.of())));
        ListGeneratedFilesTool tool = new ListGeneratedFilesTool(service);

        assertThat(tool.definition().getName()).isEqualTo("list_generated_files");
        assertThat(tool.definition().getRisk()).isEqualTo(AgentToolRisk.R0_READ_ONLY);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(new AgentToolContext(58L, 2L), Map.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");

        assertThat(result.get("count")).isEqualTo(2);
        assertThat(files.get(0).get("statusText")).isEqualTo("可打开");
        assertThat(files.get(1).get("statusText")).isEqualTo("文件已丢失，需重新生成");
        assertThat(files.get(1).get("errorMessage")).isEqualTo(AgentOutputService.MISSING_MESSAGE);
        assertThat(String.valueOf(result.get("whereToOpen"))).contains("文件卡");
    }

    @Test
    void explainsThatNothingWasGeneratedWhenListIsEmpty() {
        AgentOutputService service = mock(AgentOutputService.class);
        when(service.listMine(2L)).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) new ListGeneratedFilesTool(service)
                .execute(new AgentToolContext(1L, 2L), Map.of());

        assertThat(result.get("count")).isEqualTo(0);
        assertThat(String.valueOf(result.get("note"))).contains("并没有真正生成");
    }
}
