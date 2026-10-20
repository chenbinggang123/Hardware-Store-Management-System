package com.example.demo.billing;

import com.example.demo.agent.service.AgentOutputService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BillingImportServiceTest {
    BillingImportService importer;AgentOutputService outputs;
    @BeforeEach void setup(){outputs=mock(AgentOutputService.class);BillingService billing=new BillingService(null,null,null,null,new ObjectMapper());importer=new BillingImportService(outputs,billing,new ObjectMapper());}
    Map<String,Object> workbook(List<String> columns,List<List<String>> rows){return new LinkedHashMap<>(Map.of("outputId",8L,"fileName","测试单据.xlsx","sheets",List.of(Map.of("columns",columns,"rows",rows,"truncated",false)),"info",List.of(Map.of("label","日期","value","2026-10-02"))));}
    @Test void importsOwnedCompleteRowsPreservesEvidenceAndDoesNotInventUnitOrPrice(){var w=workbook(List.of("项目","单位","数量","单价","票面金额"),List.of(List.of("示例材料甲","","5","17.00","80.00"),List.of("其他","套","2","","未识别")));when(outputs.readWorkbookContent(8L,4L,null,201)).thenReturn(w);var c=importer.preview(4L,8L);verify(outputs).readWorkbookContent(8L,4L,null,201);assertThat(c.getDate()).isEqualTo("2026-10-02");assertThat(c.getLines().get(0).getAmount()).isEqualByComparingTo("85.00");assertThat(c.getLines().get(0).getSourceAmount()).isEqualTo("80.00");assertThat(c.getLines().get(0).getUnit()).isEmpty();assertThat(c.getLines().get(1).getPrice()).isNull();assertThat(c.getSourceNote()).contains("测试单据.xlsx","80.00","未识别");assertThat(c.getLines()).noneMatch(BillingContent.Line::isRemember);}
    @Test void refusesMultipleSheetsUnknownFieldsDuplicatesAndTruncatedRows(){var w=workbook(List.of("项目","数量","单价","折扣"),List.of(List.of("A","2","5","0.57")));assertThatThrownBy(()->importer.convert(w)).hasMessageContaining("折扣");w.put("sheets",List.of(Map.of(),Map.of()));assertThatThrownBy(()->importer.convert(w)).hasMessageContaining("多工作表");var truncated=workbook(List.of("项目","数量","单价"),List.of(List.of("A","2","5")));truncated.put("sheets",List.of(Map.of("columns",List.of("项目","数量","单价"),"rows",List.of(List.of("A","2","5")),"truncated",true)));assertThatThrownBy(()->importer.convert(truncated)).hasMessageContaining("完整明细");}
    @Test void doesNotSilentlyCoerceUncertainNumbers(){var w=workbook(List.of("项目","数量","单价"),List.of(List.of("A","疑似20","5")));assertThatThrownBy(()->importer.convert(w)).hasMessageContaining("第1行数量");}
    @Test void reimportingGeneratedBillKeepsOriginalEvidenceAmount(){var w=workbook(List.of("商品名称","数量","单价","单据金额","来源金额原文","核算金额"),List.of(List.of("A","2","5","10.00","8.00","10.00")));assertThat(importer.convert(w).getLines().get(0).getSourceAmount()).isEqualTo("8.00");}
}
