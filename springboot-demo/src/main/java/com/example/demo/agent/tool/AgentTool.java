package com.example.demo.agent.tool;

import java.util.Map;

public interface AgentTool {
    AgentToolDefinition definition();

    default Object approvalPreview(AgentToolContext context, Map<String, Object> arguments) {
        return arguments;
    }

    /**
     * 仅对 R1 草稿工具生效：草稿可在业务页面自由修改、没有任何业务副作用时返回 true，
     * 由策略直接放行，正式保存仍由用户在业务页面完成。
     */
    default boolean draftWithoutApproval() {
        return false;
    }

    /** 执行成功后直接结束本次运行，不再回到模型生成说明。 */
    default boolean completesRun() {
        return false;
    }

    Object execute(AgentToolContext context, Map<String, Object> arguments);
}
