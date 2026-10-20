package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.agent.workbook.AgentWorkbookService;
import com.example.demo.agent.workbook.AgentWorkbookSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Lets the model turn any table it has organised in the conversation (recognised receipts,
 * query results, lists the user dictated) into a downloadable Excel file. The file is a draft
 * deliverable: it is previewed, confirmed by the user, kept until the user deletes it, and never
 * writes business data.
 */
@Component
public class GenerateExcelWorkbookTool implements AgentTool {
    private final AgentWorkbookService service;
    private final ObjectMapper objectMapper;

    public GenerateExcelWorkbookTool(AgentWorkbookService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentToolDefinition definition() {
        Map<String, Object> columnSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "name", Map.of("type", "string", "description", "表头名称"),
                        "type", Map.of("type", "string", "enum", List.of("TEXT", "NUMBER", "INTEGER", "MONEY", "DATE"),
                                "description", "列类型。单号、条码、规格、电话等必须用 TEXT 以保留原文和前导零；数量用 NUMBER；单价和金额用 MONEY；日期用 DATE"),
                        "role", Map.of("type", "string", "enum", List.of("NONE", "QUANTITY", "UNIT_PRICE", "AMOUNT"),
                                "description", "数量、单价、金额列必须分别标注 QUANTITY、UNIT_PRICE、AMOUNT，系统会用数量×单价验算金额并自动求和")),
                "required", List.of("name"));
        Map<String, Object> sheetSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "name", Map.of("type", "string", "description", "工作表名称，例如 商品明细"),
                        "columns", Map.of("type", "array", "minItems", 1, "maxItems", 50, "items", columnSchema),
                        "rows", Map.of("type", "array", "maxItems", 2000,
                                "description", "数据行。每行是与表头顺序一致的数组，单元格为字符串、数字或 null；数字列直接填数字，不要带单位或货币符号",
                                "items", Map.of("type", "array", "items", Map.of("type", List.of("string", "number", "null")))),
                        "totalColumns", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "需要在表尾求和的数字列名；金额列会自动求和"),
                        "note", Map.of("type", "string", "description", "写在工作表末尾的说明，可省略")),
                "required", List.of("name", "columns", "rows"));
        Map<String, Object> infoSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "label", Map.of("type", "string", "description", "项目名，如 开票方、单号、日期、票面合计原文"),
                        "value", Map.of("type", "string", "description", "内容原文")),
                "required", List.of("label", "value"));
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "title", Map.of("type", "string", "description", "简短业务标题，可含已确认公司名和单号；不要图片随机名、哈希或时间戳。后端统一生成文件名"),
                        "layout", Map.of("type", "string", "enum", List.of("TABLE", "RECEIPT"), "description", "收款收据使用 RECEIPT：纸单版式（序号/商品/规格/单位/数量/单价/金额/备注，无折扣，底部合计与签收）和关联明细页；普通表格用 TABLE。info 提供公司名称、单号、日期、客户名称、电话、地址、票面合计、票面大写原文；未知留空。明细必须包含项目、单位、数量、单价、票面金额，未识别金额留空"),
                        "attachmentId", Map.of("type", List.of("integer", "null"),
                                "description", "内容来源的对话附件 ID；内容来自附件时必须填写，否则为 null"),
                        "sheets", Map.of("type", "array", "minItems", 1, "maxItems", 10, "items", sheetSchema),
                        "info", Map.of("type", "array", "items", infoSchema,
                                "description", "单据级信息，如 开票方、单号、日期、客户、票面合计原文；会写入“单据信息”工作表"),
                        "includeSourceText", Map.of("type", "boolean",
                                "description", "是否把附件识别原文附为单独工作表，便于用户对照核对；默认 false"),
                        "note", Map.of("type", "string", "description", "写入“生成说明”的补充说明，可省略")),
                "required", List.of("title", "sheets"));
        return new AgentToolDefinition(
                "generate_excel_workbook",
                "把对话中已经整理好的内容（识别出的单据明细、查询结果或用户口述的清单）生成一份可编辑的 Excel 文件；"
                        + "文件永久保存并显示在对话中，用户可自行删除；只生成文件，不修改商品、库存、价格、订单或账本",
                AgentToolRisk.R1_DRAFT,
                schema,
                "确认生成 Excel 文件");
    }

    @Override
    public Object approvalPreview(AgentToolContext context, Map<String, Object> arguments) {
        return service.preview(context.getRunId(), context.getOperatorId(), spec(arguments));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        return service.generate(context.getRunId(), context.getOperatorId(), spec(arguments));
    }

    private AgentWorkbookSpec spec(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) throw new IllegalArgumentException("Excel 内容不能为空");
        try {
            return objectMapper.convertValue(arguments, AgentWorkbookSpec.class);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Excel 参数格式不正确：" + exception.getMessage(), exception);
        }
    }
}
