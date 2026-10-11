# 对话生成 Excel 文件设计与开发记录

> 文档类型：当前实现说明 / 开发记录
>
> 适用范围：`Hardware-Store-Management-System` 中基于 Spring Boot 的正式经营 Agent（小五）与微信小程序
>
> 实施日期：2026-10-12
>
> 相关决策：[ADR-0006](./adr/ADR-0006-对话生成文件采用确认快照与永久输出记录.md)

## 1. 目标与边界

用户在小五对话里上传单据图片、表格或文档，或者直接口述需要的表格内容后，可以说“做成 Excel”，小五整理内容、展示确认卡，用户确认后得到一份可编辑、可转发的 XLSX 文件。

本轮确定的产品规则：

- 表格结构由用户意图决定，不限于单据明细；工具接受任意表头和行。
- 文件永久保存，不设有效期；用户在对话或“我的文件”页自行删除。
- 生成文件只是文件，不写入商品、库存、价格、订单或账本；需要写入时仍走原有审批工具。
- 模型只负责整理内容；数字解析、金额验算、求和、公式注入防护、文件命名和存储都由确定性 Java 代码完成。

## 2. 主流程

```text
附件 OCR / 查询结果 / 用户口述
  → 小五用 Markdown 表格展示将写入文件的内容
  → 模型调用 generate_excel_workbook（R1_DRAFT）
  → AgentWorkbookService.preview 校验、验算，生成确认卡
  → 用户在小程序确认
  → AgentWorkbookService.generate 同步生成 XLSX，写 excel_task_output，存 AttachmentStorage
  → Run 结果与会话历史携带 files，小程序显示文件卡
  → 用户打开（wx.openDocument）、转发或删除
```

## 3. 后端实现

### 3.1 新增与修改的代码

| 位置 | 说明 |
| --- | --- |
| `excel/render/WorkbookRenderSupport` | 从原可编辑 Excel 导出中抽出的公共渲染规则：表头样式、公式转义、数字列判断、工作表名去重、冻结与列宽 |
| `excel/service/ExcelWorkbookExportService` | 改为复用 `WorkbookRenderSupport`，行为不变 |
| `agent/workbook/AgentWorkbookSpec` | 模型提交的表格描述：标题、来源附件、工作表、表头类型与角色、行、单据信息、是否附带识别原文 |
| `agent/workbook/AgentWorkbookService` | 校验、规范化、验算、渲染、落库、幂等与审计 |
| `agent/service/AgentOutputService` | 小五生成文件的列表、下载校验、删除 |
| `agent/tool/builtin/GenerateExcelWorkbookTool` | 模型工具 `generate_excel_workbook`，风险 `R1_DRAFT`，确认标题“确认生成 Excel 文件” |
| `agent/api/AgentOutputController` | `/agent/outputs` 列表、详情、下载、删除，以及 `/agent/runs/{runId}/outputs` |
| `agent/dto/AgentFileView` | 对话与文件页共用的文件视图 |
| `agent/harness/AgentRunResult`、`AgentRunResultFactory` | Run 结果新增 `files` |
| `agent/dto/AgentConversationMessage`、`AgentConversationService` | 会话历史中的助手消息携带 `files` |
| `agent/model/OpenAiCompatibleModelGateway`、`AgentAttachmentService` | 系统提示词与附件提示增加生成 Excel 的规则 |

### 3.2 工具契约

```json
{
  "title": "恒迪模具五金收款收据0002766",
  "attachmentId": 391,
  "sheets": [
    {
      "name": "商品明细",
      "columns": [
        {"name": "行号", "type": "INTEGER"},
        {"name": "项目", "type": "TEXT"},
        {"name": "单位", "type": "TEXT"},
        {"name": "数量", "role": "QUANTITY"},
        {"name": "单价", "role": "UNIT_PRICE"},
        {"name": "金额", "role": "AMOUNT"}
      ],
      "rows": [[1, "钻头 2.2", "支", 10, 1.5, 15], [7, "气管 10", "米", 28, 1.8, 504]],
      "totalColumns": ["金额"]
    }
  ],
  "info": [{"label": "开票方", "value": "恒迪模具五金"}, {"label": "票面合计原文", "value": "2451"}],
  "includeSourceText": true
}
```

列类型与角色：

| 字段 | 取值 | 处理 |
| --- | --- | --- |
| `type` | `TEXT` | 原样写入文本，单号、条码、规格、电话保留前导零 |
| `type` | `NUMBER` / `INTEGER` | 解析为数字单元格，不能解析时按文本保留并写入核对说明 |
| `type` | `MONEY` | 数字单元格，格式 `#,##0.00` |
| `type` | `DATE` | 支持 `2026-10-6`、`2026/10/6`、`2026年10月6日`，解析失败按文本保留 |
| `role` | `QUANTITY` + `UNIT_PRICE` + `AMOUNT` | 三者齐全时逐行用 BigDecimal 验算，金额缺失自动补算，不一致时标黄并写入核对说明，金额列自动求和 |

`columns` 也接受纯字符串表头；缺少 `type` 时按角色和表头关键词推断，推断不出的一律按 `TEXT`。

### 3.3 限制与校验

| 项目 | 限制 |
| --- | --- |
| 工作表数 | 最多 10 个 |
| 列数 | 每表最多 50 列 |
| 行数 | 每表最多 2000 行，所有工作表合计最多 5000 行，空行自动跳过 |
| 单元格 | 最多 2000 字，超出截断并提示 |
| 单据信息 | 最多 40 项 |
| 附件原文 | 最多 3000 行 |

确认卡（`preview.kind = EXCEL_WORKBOOK`）展示文件名、工作表与行数、表头、每表前 5 行样例、金额待核对数、来源附件、保存方式和警告。

### 3.4 生成的文件

| 工作表 | 内容 |
| --- | --- |
| 用户定义的工作表 | 表头冻结、自动筛选、数字单元格、求和公式、黄色标注的验算差异、`核对说明` 列（仅在有说明时出现） |
| `单据信息` | `info` 中的项目与内容，仅在提供时出现 |
| `附件原文` | 来源附件的识别原文逐行写入，仅在 `includeSourceText` 且附件有原文时出现 |
| `生成说明` | 标题、生成时间、操作人、任务 ID、来源附件名与 SHA-256、工作表、行数、验算差异数、提示和声明 |

安全规则：以 `=`、`+`、`-`、`@` 开头的文本统一加引号前缀；求和公式由系统写入并预先计算缓存值，微信预览也能显示合计；文件名去除非法字符并限制长度。

### 3.5 数据与存储

`excel_task_output` 泛化为统一输出记录：

| 列 | 说明 |
| --- | --- |
| `task_id` | 改为允许为空 |
| `source_type` | `EXCEL_TASK` 或 `AGENT_WORKBOOK` |
| `source_id` | 对话生成文件时记录来源附件 ID |
| `run_id`、`conversation_id` | 关联 Agent 任务与会话 |
| `expires_at` | 对话生成文件为空，表示永久保存 |

`schema.sql` 启动时通过元数据判断补列补索引；已有环境也可执行 `migrations/20261012_agent_workbook_output.sql`。文件对象保存在 `agent-outputs/{operatorId}/{yyyyMM}/` 前缀下，复用 `AttachmentStorage`（本地或 COS）。

幂等键由 `runId` 与参数摘要生成，模型不能指定；同一确认重复执行返回同一文件。生成与删除都写入 `operation_log`，模块 `AGENT_FILE`。

### 3.6 HTTP 接口

```http
GET    /api/agent/outputs                      当前用户在对话中生成的文件
GET    /api/agent/outputs/{outputId}           文件详情
GET    /api/agent/outputs/{outputId}/download  下载（仅限本人、READY 状态）
DELETE /api/agent/outputs/{outputId}           删除
POST   /api/agent/outputs/{outputId}/delete    删除（网关不转发 DELETE 时的兼容入口）
GET    /api/agent/runs/{runId}/outputs         某次任务生成的文件
```

`GET /api/agent/runs/{runId}` 与 `GET /api/agent/conversations/{conversationId}` 的返回中新增 `files`。

## 4. 小程序实现

- `utils/agent-files.js`：文件视图、下载后 `wx.openDocument` 打开、带确认的删除。
- `pages/agent`：确认卡新增 Excel 预览分支；助手消息下方显示文件卡，可打开或删除；结果区新增“我的文件”；会话抽屉新增“小五生成的文件”入口。
- `pages/agent-files`：永久文件列表，支持下拉刷新、打开和删除，并在 `app.json` 注册。
- `utils/api.js`：`getAgentOutputs`、`getAgentRunOutputs`、`downloadAgentOutput`、`deleteAgentOutput`。

## 5. 测试

| 测试 | 覆盖 |
| --- | --- |
| `AgentWorkbookServiceTest` | 验算差异标黄与说明、缺失金额补算、求和公式与缓存值、公式注入转义、文本列保留前导零、附件原文与生成说明、幂等、越权附件降级为警告、规模限制、数字与日期解析 |
| `AgentOutputServiceTest` | 删除本人文件并清理存储、存储失败仍删除记录、拒绝他人与文件任务输出、下载前状态校验、列表只含对话生成文件 |
| `GenerateExcelWorkbookToolTest` | 工具定义、嵌套参数与纯字符串表头的转换、空参数拒绝 |
| `AgentOutputControllerTest` | 列表、两种删除入口、下载流与附件头 |
| `AgentRunResultFilesTest` | Run 结果与会话历史携带文件 |
| `ExcelWorkbookExportServiceTest` | 渲染抽取后原有导出行为不变 |

运行方式：

```powershell
cd springboot-demo
..\tools\apache-maven-3.9.14\bin\mvn.cmd test
```

集成测试需要本地 MySQL 库 `hardware_store_test`。

## 6. 验收清单

- 上传样例收据图片，让小五“做成 Excel”，确认卡显示 7 行明细与金额待核对数，确认后对话出现文件卡，微信内可打开。
- 文件含用户工作表、`单据信息`、`附件原文`、`生成说明`；数量、单价、金额是数字单元格，表尾合计为公式。
- 票面合计与验算合计不一致时只标注，不改写原文。
- 他人账号不能下载或删除；删除后列表与对话中同步消失。
- 不写入商品、库存、价格、订单或账本。

## 7. 后续计划

1. 识别结构落库：在 `agent_attachment` 保存腾讯表格识别的单元格、置信度和坐标，Excel 增加“OCR 原始表格”工作表。
2. 统一 OCR 栈：实现基于腾讯 SDK 的 `TableOcrProvider`，让图片文件任务在生产环境可用。
3. 附件转文件任务：新增 `create_file_task_from_attachment` 工具，把对话附件接入字段映射、逐行复核和正式提交链路。
4. 同源 PDF：基于同一份确认快照输出 PDF，供打印和转发。
