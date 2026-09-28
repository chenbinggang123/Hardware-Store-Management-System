package com.example.demo.agent.policy;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolRisk;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AgentPolicyEngine {
    public AgentPolicyDecision evaluate(AgentToolContext context, AgentTool tool, Map<String, Object> arguments) {
        if (context.getOperatorId() == null) {
            throw new IllegalArgumentException("Agent 工具缺少有效操作人");
        }
        if (arguments == null) {
            throw new IllegalArgumentException("工具参数不能为空");
        }
        AgentToolRisk risk = tool.definition().getRisk();
        if (risk == AgentToolRisk.R0_READ_ONLY) {
            return AgentPolicyDecision.ALLOW;
        }
        if (risk == AgentToolRisk.R1_DRAFT || risk == AgentToolRisk.R2_WRITE) {
            return AgentPolicyDecision.REQUIRE_APPROVAL;
        }
        throw new IllegalArgumentException("当前不允许执行敏感或受限工具：" + tool.definition().getName());
    }
}
