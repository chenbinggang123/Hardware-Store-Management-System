package com.example.demo.agent;

import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.agent.tool.builtin.ReadGeneratedFileTool;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadGeneratedFileToolTest {

    @Test
    void readsThroughTheOutputServiceWithParsedArguments() {
        AgentOutputService service = mock(AgentOutputService.class);
        when(service.readWorkbookContent(7L, 2L, "商品明细", 50)).thenReturn(Map.of("outputId", 7L));
        ReadGeneratedFileTool tool = new ReadGeneratedFileTool(service);
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("outputId", "7");
        arguments.put("sheetName", "商品明细");
        arguments.put("maxRows", 50);

        assertThat(tool.definition().getName()).isEqualTo("read_generated_file");
        assertThat(tool.definition().getRisk()).isEqualTo(AgentToolRisk.R0_READ_ONLY);
        assertThat(tool.definition().getInputSchema().get("required")).isEqualTo(List.of("outputId"));
        assertThat(tool.execute(new AgentToolContext(1L, 2L), arguments)).isEqualTo(Map.of("outputId", 7L));
        verify(service).readWorkbookContent(7L, 2L, "商品明细", 50);
    }

    @Test
    void requiresAnOutputId() {
        ReadGeneratedFileTool tool = new ReadGeneratedFileTool(mock(AgentOutputService.class));
        assertThatThrownBy(() -> tool.execute(new AgentToolContext(1L, 2L), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputId");
    }
}
