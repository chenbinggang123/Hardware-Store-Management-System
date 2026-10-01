package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.dto.CommitSalesDraftRequest;
import com.example.demo.agent.dto.SalesDraftPreview;
import com.example.demo.agent.service.SalesDraftService;
import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CommitSalesDraftTool implements AgentTool {
    private final SalesDraftService salesDraftService;

    public CommitSalesDraftTool(SalesDraftService salesDraftService) {
        this.salesDraftService = salesDraftService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "commit_sales_draft",
                "将销售草稿提交为正式销售单并更新客户欠款；不会自动出库",
                AgentToolRisk.R2_WRITE,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "draftId", Map.of("type", "integer", "description", "销售草稿 ID"),
                                "draftVersion", Map.of("type", "integer", "description", "草稿预览中的版本号")),
                        "required", List.of("draftId", "draftVersion")),
                "确认提交正式销售单");
    }

    @Override
    public Object approvalPreview(AgentToolContext context, Map<String, Object> arguments) {
        SalesDraftPreview preview = salesDraftService.getDraft(requiredLong(arguments, "draftId"), context.getOperatorId());
        if (!requiredLong(arguments, "draftVersion").equals(preview.getVersion())) {
            throw new IllegalArgumentException("销售草稿已发生变化，请重新查看后再提交");
        }
        if (!preview.isInventorySufficient()) {
            throw new IllegalArgumentException("销售草稿存在库存不足，不能提交正式订单");
        }
        return Map.of(
                "draftId", preview.getDraftId(),
                "draftVersion", preview.getVersion(),
                "customerName", preview.getCustomerName(),
                "totalAmount", preview.getTotalAmount(),
                "receivedAmount", preview.getReceivedAmount(),
                "newDebtAmount", preview.getNewDebtAmount(),
                "debtAfterCommit", preview.getDebtAfterCommit(),
                "inventorySufficient", preview.isInventorySufficient(),
                "items", preview.getItems(),
                "effect", "创建正式销售单并更新客户欠款；本操作不扣减库存");
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        Long draftId = requiredLong(arguments, "draftId");
        CommitSalesDraftRequest request = new CommitSalesDraftRequest();
        request.setDraftVersion(requiredLong(arguments, "draftVersion"));
        request.setIdempotencyKey("agent-run-" + context.getRunId() + "-commit-draft-" + draftId);
        return salesDraftService.commitDraft(draftId, request, context.getOperatorId());
    }

    private Long requiredLong(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " 必须是整数");
        }
        return number.longValue();
    }
}
