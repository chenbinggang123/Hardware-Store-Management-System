package com.example.demo.agent;
import com.example.demo.agent.tool.*;
import com.example.demo.agent.tool.builtin.RecordLedgerPaymentTool;
import com.example.demo.dto.FactoryLedgerTransactionRequest;
import com.example.demo.service.FactoryLedgerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class RecordLedgerPaymentToolTest {
    @Test void onlyPaymentCanBeWrittenAndPreviewDoesNotWrite() {
        var service=mock(FactoryLedgerService.class);var tool=new RecordLedgerPaymentTool(service);var context=new AgentToolContext(1L,7L);
        var args=Map.<String,Object>of("ledgerId",3,"amount",20,"date","2026-10-23","type","PURCHASE");
        tool.approvalPreview(context,args);verify(service,never()).addAgentTransaction(any(),any(),any());
        tool.execute(context,args);var captured=ArgumentCaptor.forClass(FactoryLedgerTransactionRequest.class);
        verify(service).addAgentTransaction(eq(3L),captured.capture(),eq(7L));assertThat(captured.getValue().getType()).isEqualTo("PAYMENT");
        assertThat(tool.definition().getRisk()).isEqualTo(AgentToolRisk.R2_WRITE);
    }
}
