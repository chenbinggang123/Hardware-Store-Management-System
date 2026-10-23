package com.example.demo.agent;
import com.example.demo.agent.tool.*;
import com.example.demo.agent.tool.builtin.QueryProcurementReceiptsTool;
import com.example.demo.entity.*;
import com.example.demo.repository.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QueryProcurementReceiptsToolTest {
    private InventoryLog event(long id,String type,int quantity) {
        var log=new InventoryLog();log.setProductId(id);log.setProductName("螺丝"+id);log.setChangeType(type);log.setQuantity(quantity);log.setRelatedOrderId(99L);return log;
    }
    @Test void queriesActualSaveDayAndCurrentOperatorAndSeparatesWithdrawals() {
        var logs=mock(InventoryLogRepository.class);var products=mock(ProductRepository.class);
        var day=LocalDate.of(2026,10,23);
        when(logs.findByOperatorIdAndChangeTypeInAndCreateTimeGreaterThanEqualAndCreateTimeLessThanOrderByCreateTimeAsc(eq(7L),any(),eq(day.atStartOfDay()),eq(day.plusDays(1).atStartOfDay())))
                .thenReturn(List.of(event(1,"采购单入库",100),event(1,"采购单撤回",-20),event(2,"采购单撤回",-5)));
        var p=new Product();p.setId(1L);p.setSpec("M6");p.setUnit("个");when(products.findAllById(any())).thenReturn(List.of(p));
        var tool=new QueryProcurementReceiptsTool(logs,products);
        Map<?,?> result=(Map<?,?>)tool.execute(new AgentToolContext(1L,7L),Map.of("date","2026-10-23"));
        List<?> items=(List<?>)result.get("items");Map<?,?> first=(Map<?,?>)items.get(0),second=(Map<?,?>)items.get(1);
        assertThat(first.get("receivedQuantity")).isEqualTo(100L);assertThat(first.get("withdrawnQuantity")).isEqualTo(20L);assertThat(first.get("netQuantity")).isEqualTo(80L);
        assertThat(first.get("specification")).isEqualTo("M6");assertThat(first.get("documentIds")).asList().contains(99L);
        assertThat(second.get("netQuantity")).isEqualTo(-5L);assertThat(result.get("scope")).isEqualTo("当前操作人");
        assertThat(tool.definition().getRisk()).isEqualTo(AgentToolRisk.R0_READ_ONLY);
    }
    @Test void paginatesGroupsWithoutPretendingFirstPageIsComplete() {
        var logs=mock(InventoryLogRepository.class);var products=mock(ProductRepository.class);var events=new ArrayList<InventoryLog>();
        for(int i=1;i<=21;i++)events.add(event(i,"采购单入库",1));
        when(logs.findByOperatorIdAndChangeTypeInAndCreateTimeGreaterThanEqualAndCreateTimeLessThanOrderByCreateTimeAsc(any(),any(),any(),any())).thenReturn(events);
        var tool=new QueryProcurementReceiptsTool(logs,products);var context=new AgentToolContext(1L,7L);
        Map<?,?> result=(Map<?,?>)tool.execute(context,Map.of("page",1));
        assertThat(result.get("items")).asList().hasSize(1);assertThat(result.get("totalProducts")).isEqualTo(21);assertThat(result.get("totalPages")).isEqualTo(2);
        assertThatThrownBy(()->tool.execute(context,Map.of("date","2026-99-99"))).hasMessageContaining("查询日期");
        assertThatThrownBy(()->tool.execute(context,Map.of("page",-1))).hasMessageContaining("页码");
    }
}
