package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.*;
import com.example.demo.billing.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ReadBillingDocumentsTool implements AgentTool {
    private final BillingService billing;
    private final BillingLedgerService records;
    public ReadBillingDocumentsTool(BillingService billing,BillingLedgerService records){this.billing=billing;this.records=records;}
    public AgentToolDefinition definition(){return new AgentToolDefinition("read_billing_documents","查询统一单据编辑页中的采购、拿货和退货记录；可按厂商、关键词查询，或按documentId读取完整明细。",AgentToolRisk.R0_READ_ONLY,
            Map.of("type","object","additionalProperties",false,"properties",Map.of(
                    "keyword",Map.of("type","string"),"factory",Map.of("type","string"),
                    "documentType",Map.of("type","string","enum",List.of("PROCUREMENT","PURCHASE","RETURN","")),
                    "status",Map.of("type","string","enum",List.of("DRAFT","CONFIRMED","VOIDED","")),
                    "documentId",Map.of("type","integer","minimum",1),"page",Map.of("type","integer","minimum",0)),"required",List.of()));}
    public Object execute(AgentToolContext context,Map<String,Object> args){
        if(args.get("documentId") instanceof Number id)return billing.get(context.getOperatorId(),id.longValue());
        return records.records(context.getOperatorId(),Objects.toString(args.get("keyword"),""),Objects.toString(args.get("status"),""),Objects.toString(args.get("documentType"),""),Objects.toString(args.get("factory"),""),args.get("page") instanceof Number page?page.intValue():0,20);
    }
}
