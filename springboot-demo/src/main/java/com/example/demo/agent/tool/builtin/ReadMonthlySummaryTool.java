package com.example.demo.agent.tool.builtin;
import com.example.demo.agent.tool.*;
import com.example.demo.billing.MonthlySummaryService;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class ReadMonthlySummaryTool implements AgentTool {
    private final MonthlySummaryService service;
    public ReadMonthlySummaryTool(MonthlySummaryService service){this.service=service;}
    public AgentToolDefinition definition(){return new AgentToolDefinition("read_monthly_summary","读取统一单据和账本的月度汇总；开单金额按单号归月、扣退货，累计欠款截至今天。不是旧销售报表，不代表利润或今日入库。",AgentToolRisk.R0_READ_ONLY,Map.of("type","object","additionalProperties",false,"properties",Map.of("month",Map.of("type","string","description","YYYY-MM，省略为当月")),"required",List.of()));}
    public Object execute(AgentToolContext context,Map<String,Object> args){return service.summary(Objects.toString(args.get("month"),""));}
}
