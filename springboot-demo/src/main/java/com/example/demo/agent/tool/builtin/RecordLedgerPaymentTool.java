package com.example.demo.agent.tool.builtin;
import com.example.demo.agent.tool.*;
import com.example.demo.dto.FactoryLedgerTransactionRequest;
import com.example.demo.service.FactoryLedgerService;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
@Component
public class RecordLedgerPaymentTool implements AgentTool {
    private final FactoryLedgerService service;
    public RecordLedgerPaymentTool(FactoryLedgerService service){this.service=service;}
    public AgentToolDefinition definition(){return new AgentToolDefinition("record_ledger_payment","仅登记账本收付款：客户账本是收款，供应商账本是付款。先查询唯一账本，展示金额影响并确认。拿货、退货、采购一律使用开单草稿。",AgentToolRisk.R2_WRITE,Map.of("type","object","additionalProperties",false,"properties",Map.of("ledgerId",Map.of("type","integer","minimum",1),"amount",Map.of("type","number","exclusiveMinimum",0),"date",Map.of("type","string","description","YYYY-MM-DD"),"remark",Map.of("type","string")),"required",List.of("ledgerId","amount","date")),"确认登记收付款");}
    private Long id(Map<String,Object> args){if(!(args.get("ledgerId") instanceof Number n)||n.longValue()<=0)throw new IllegalArgumentException("请先选择唯一账本");return n.longValue();}
    private FactoryLedgerTransactionRequest request(Map<String,Object> args){var request=new FactoryLedgerTransactionRequest();request.setType("PAYMENT");request.setDate(LocalDate.parse(Objects.toString(args.get("date"),"")));request.setPaymentAmount(new BigDecimal(Objects.toString(args.get("amount"),"0")));request.setRemark(Objects.toString(args.get("remark"),""));return request;}
    public Object approvalPreview(AgentToolContext context,Map<String,Object> args){return service.previewTransaction(id(args),request(args));}
    public Object execute(AgentToolContext context,Map<String,Object> args){return service.addAgentTransaction(id(args),request(args),context.getOperatorId());}
}
