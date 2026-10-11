package com.example.demo.agent;

import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.agent.tool.builtin.GenerateExcelWorkbookTool;
import com.example.demo.agent.workbook.AgentWorkbookService;
import com.example.demo.agent.workbook.AgentWorkbookSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateExcelWorkbookToolTest {

    @Test
    void definitionIsDraftLevelAndRequiresTitleAndSheets() {
        GenerateExcelWorkbookTool tool = new GenerateExcelWorkbookTool(mock(AgentWorkbookService.class), new ObjectMapper());

        var definition = tool.definition();

        assertThat(definition.getName()).isEqualTo("generate_excel_workbook");
        assertThat(definition.getRisk()).isEqualTo(AgentToolRisk.R1_DRAFT);
        assertThat(definition.getApprovalTitle()).isEqualTo("确认生成 Excel 文件");
        assertThat(definition.getInputSchema().get("required")).isEqualTo(List.of("title", "sheets"));
    }

    @Test
    void convertsModelArgumentsIncludingPlainHeaderStrings() {
        AgentWorkbookService service = mock(AgentWorkbookService.class);
        when(service.preview(eq(7L), eq(2L), any())).thenReturn(Map.of("kind", "EXCEL_WORKBOOK"));
        GenerateExcelWorkbookTool tool = new GenerateExcelWorkbookTool(service, new ObjectMapper());
        Map<String, Object> arguments = Map.of(
                "title", "库存清单",
                "attachmentId", 5,
                "sheets", List.of(Map.of(
                        "name", "清单",
                        "columns", List.of("商品", Map.of("name", "数量", "role", "QUANTITY")),
                        "rows", List.of(List.of("电钻", 3)))),
                "info", List.of(Map.of("label", "日期", "value", "2026-10-12")));

        Object preview = tool.approvalPreview(new AgentToolContext(7L, 2L), arguments);

        assertThat(preview).isEqualTo(Map.of("kind", "EXCEL_WORKBOOK"));
        ArgumentCaptor<AgentWorkbookSpec> captor = ArgumentCaptor.forClass(AgentWorkbookSpec.class);
        verify(service).preview(eq(7L), eq(2L), captor.capture());
        AgentWorkbookSpec spec = captor.getValue();
        assertThat(spec.getTitle()).isEqualTo("库存清单");
        assertThat(spec.getAttachmentId()).isEqualTo(5L);
        assertThat(spec.getSheets()).hasSize(1);
        assertThat(spec.getSheets().get(0).getColumns().get(0).getName()).isEqualTo("商品");
        assertThat(spec.getSheets().get(0).getColumns().get(1).getRole()).isEqualTo("QUANTITY");
        assertThat(spec.getSheets().get(0).getRows()).hasSize(1);
        assertThat(spec.getInfo().get(0).getLabel()).isEqualTo("日期");
    }

    @Test
    void rejectsEmptyArgumentsBeforeReachingTheService() {
        GenerateExcelWorkbookTool tool = new GenerateExcelWorkbookTool(mock(AgentWorkbookService.class), new ObjectMapper());
        assertThatThrownBy(() -> tool.execute(new AgentToolContext(7L, 2L), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }
}
