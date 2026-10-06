# 借鉴 WPS AI 的采购文档填写与双格式输出设计

> 文档类型：产品与技术设计 / 待评审  
> 适用项目：`Hardware-Store-Management-System` 及正式 Spring Boot 经营 Agent  
> 设计日期：2026-10-06  
> 首个样例：横向 A4《采购明细》PDF，商品按三块区域排列，原表包含商品名称、单位和一个空白填写格  
> 目标：用户上传采购明细 PDF、Excel、CSV 或图片后，通过自然语言补充数量和价格，经结构化复核后生成可下载的 `.xlsx` 和 `.pdf`

## 0. 当前实现状态（2026-10-06）

本轮已经完成固定模板 MVP 的主链路：

- 附件入口支持 DOC、DOCX、XLS、XLSX、CSV 和文字型 PDF；文件任务可把 Word 表格、Excel 表格和采购 PDF 统一转换成 `ParsedWorkbook`。Word/PDF 文件任务目前只允许用于“买家询价”。
- `PdfProcurementParser` 按页面坐标把横向三栏清单还原为左、中、右三个逻辑工作表，统一列为“商品名称 / 单位 / 数量”。
- 样例《配件采购明细表》实测识别 18 + 20 + 12，共 50 行，顺序固定为左栏、中栏、右栏。
- PDF 的规范字段在小程序中自动映射，用户上传后直接进入商品复核，减少重复确认步骤。
- 报价请求新增 `outputFormat = XLSX | PDF`；小程序默认同时生成可编辑 XLSX 和横向三栏 PDF，也可以关闭其中一种。
- PDF 报价包含名称、单位、数量、单价、小计、客户、计价方式、日期和总金额；生成结果沿用现有输出记录、7 天有效期、鉴权下载和异步状态。
- 小五附件支持 PDF 文字提取，并新增 `list_file_tasks`、`generate_quote_files` 两个受控工具。
- 新增统一的 `generate-workbook` 接口和 `export_file_task_to_excel` 工具：无需商品匹配即可把 Word 表格、采购 PDF、CSV 或原生 Excel 任务生成规范、可编辑的 XLSX。
- 小程序任务详情新增“导出为可编辑 Excel”，生成结果与报价文件共用输出状态、鉴权下载和 7 天有效期。
- 完整后端测试 133 项通过；覆盖 DOCX 正文/表格提取、DOCX 表格任务解析、样例 PDF 解析、统一 XLSX 生成、数字单元格及公式注入防护，样例报价 PDF 渲染已人工检查。

本轮尚未实现：扫描 PDF OCR、自然语言逐行写入数量/手工价、同一批次原子化管理两种输出、采购入库。普通 Word 正文可供 Agent 阅读，但只有 Word 表格进入结构化 Excel；数量为空时为兼容旧报价逻辑仍按 1 生成，PDF 中会明确提示交付前核对；这项行为后续应改成可配置并优先支持逐行填写。

## 1. 结论

本功能不复制 WPS Office，也不以 WPS 灵犀作为系统的主 Agent。项目借鉴 WPS AI 的产品思路：

1. 文档是任务入口，而不是只能下载查看的附件。
2. 用户表达业务意图，Agent 负责选择受控工具完成多步操作。
3. 原始文档先转换成可校验的结构化数据，再生成新文档。
4. 所有修改都有预览、来源、版本和回退边界。
5. 同一份结构化结果可以输出 XLSX、PDF 等多种格式。
6. 模型负责理解和解释，确定性程序负责金额、格式、权限、幂等和最终文件。

结合当前项目，首版应复用已经存在的 Excel 文件任务、商品匹配、逐行复核、输出文件、附件存储和 Agent 审批能力，只增加 PDF 表格解析、采购文档草稿、数量/价格补全和 PDF 渲染。不要另建一套平行业务系统。

核心技术决策：

- **业务核心自主实现**：商品匹配、价格来源、金额计算、复核、文件生成记录和下载鉴权均在现有后端完成。
- **WPS 可选接入**：WPS WebOffice/OpenAPI 只作为在线预览、格式转换或 PDF 导出的可插拔提供商；未配置 WPS 时，本地实现仍能完成 MVP。
- **不直接修改原 PDF**：样例 PDF 每个商品只有一个空白格，无法同时可靠容纳数量和价格。系统保留原文件作为证据，输出统一的采购明细版式。
- **不自动入库**：文档填写和文件生成属于草稿/制品操作；采购入库仍走现有 `PURCHASE_RECEIPT` 校验、确认和正式提交链路。

## 2. 背景与用户故事

### 2.1 典型场景

采购员收到一份供应商或工厂发来的采购明细 PDF，表内已经列出配件名称和单位，但数量、采购单价为空。采购员希望直接说：

> 5 节扣 28×145 要 10 付，每付 12.5 元；料咀 8×30 要 20 个，每个 3.2 元。其余先留空，帮我生成 Excel 和 PDF。

系统应完成：

1. 识别附件和版式。
2. 提取商品名称、规格和单位。
3. 将自然语言中的数量和价格匹配到正确行。
4. 标出未填写、未匹配、单位冲突和异常价格。
5. 生成结构化预览供用户确认。
6. 确认后生成 XLSX 和 PDF。
7. 在对话中返回文件卡片和受鉴权保护的下载入口。

### 2.2 用户价值

- 不需要手工重建采购表。
- 不需要逐个复制商品名称。
- 可以复用商品库、供应商进价和历史采购价格。
- 生成结果可继续编辑，也可直接打印或转发。
- 原始文件、识别结果、修改内容和最终输出可追溯。

### 2.3 非目标

首版不实现：

- 通用 Word/PDF 排版编辑器；
- 任意 PDF 像素级原位编辑；
- 无人确认的采购下单、入库、付款或调价；
- 模型自由访问数据库、对象存储、Shell 或外部 URL；
- 为未知复杂版式承诺百分之百自动识别；
- 依赖 WPS 账号才能使用的单一路径。

## 3. 当前项目基础

### 3.1 可以直接复用

| 当前能力 | 现有实现 | 本功能复用方式 |
| --- | --- | --- |
| Agent 有限循环 | `AgentHarness` | 编排文档检查、补全、校验和输出工具 |
| 白名单工具 | `AgentToolRegistry` | 注册采购文档专用工具 |
| 风险和审批 | `AgentPolicyEngine`、`AgentApprovalService` | 输出前展示结构化预览，正式入库另行确认 |
| 附件存储 | `AttachmentStorage`、CloudBase/COS | 保存原始 PDF、图片和最终文件 |
| Excel/CSV 解析 | `ExcelTaskParser`、Apache POI | 继续处理表格源文件 |
| Excel 文件任务 | `ExcelTask*` | 复用任务、工作表、原始行、映射、复核和版本机制 |
| 商品匹配 | `ExcelTaskMappingService` | 将 PDF 解析结果送入相同候选与人工复核逻辑 |
| 输出记录 | `ExcelTaskOutput` | 扩展为 XLSX/PDF/BOTH 输出 |
| 报价 XLSX | `ExcelQuoteService` | 抽取通用工作簿样式和安全写入能力 |
| 异步生成 | `agentTaskExecutor` | 后台生成和转换大文件 |
| 鉴权下载 | `ExcelOutputController` | 校验任务和操作人后流式下载 |
| 图片 OCR | PaddleOCR 服务 | 作为扫描 PDF 和图片表格的备用路径 |

### 3.2 当前缺口

1. `AttachmentContentParser` 尚不支持 PDF。
2. `ExcelTaskParser` 只接受 XLS/XLSX/CSV，尚无统一的表格文档解析接口。
3. 当前报价输出只生成 XLSX，未生成 PDF。
4. `ExcelTaskOutput` 只能表达单个文件，缺少同一生成批次下的多格式关系。
5. 当前字段映射虽然已有 `QUANTITY`、`UNIT_PRICE`、`AMOUNT`，但缺少针对“原文档没有这些列，由用户后补”的行级编辑接口。
6. Agent 还没有文档任务工具，无法从对话内驱动现有 Excel 工作流。
7. 当前工作簿样式较基础，尚不适合直接作为对外采购明细。

## 4. 借鉴 WPS AI 的能力拆分

WPS AI 给用户的体验看起来像“一句话改文档”，但在本项目中应拆成可审计的确定性步骤：

```text
自然语言意图
    ↓
文档检查与分类
    ↓
版式/表格解析
    ↓
规范化业务行
    ↓
商品匹配与字段补全
    ↓
结构化预览与人工确认
    ↓
确定性文件渲染
    ↓
XLSX / PDF 输出与下载
```

### 4.1 “理解意图”不等于“直接改文件”

模型可以判断用户要填写数量、采购单价并输出两种格式，但不能直接计算金额或在 PDF 坐标上自由写字。模型只产生工具调用参数；Java 服务重新校验商品、单位、数量和价格。

### 4.2 “文档上下文”必须结构化

模型不应长期依赖大段 PDF 文本。解析服务需要把每个商品变成稳定的行对象：

```json
{
  "sourceRowId": 81,
  "sourcePage": 1,
  "sourceRegion": "LEFT",
  "sourceText": "5节扣28*145",
  "productName": "5节扣",
  "spec": "28*145",
  "unit": "付",
  "quantity": null,
  "unitPrice": null,
  "amount": null,
  "matchStatus": "NEEDS_REVIEW"
}
```

### 4.3 “直接编辑”在业务系统里表现为草稿

用户每次补数量或价格，实际更新的是带版本号的采购文档草稿，而不是覆盖原文件。最终 XLSX/PDF 是草稿某一版本的不可变快照。

## 5. 总体架构

```text
微信小程序 / 电脑版经营台
        ↓ 上传附件、输入自然语言
AgentMessageController
        ↓
AgentHarness + 文档任务白名单工具
        ↓
PurchaseDocumentApplicationService
        ↓
┌─────────────────────────────────────────────┐
│ DocumentInspector                           │
│ TabularDocumentParser                       │
│  ├─ ExcelTabularParser（复用 Apache POI）   │
│  ├─ TextPdfTableParser（新增）               │
│  └─ OcrTableParser（复用 PaddleOCR）         │
│                                             │
│ TemplateMatcher + RowNormalizer             │
│ ProductMatcher + PriceResolver               │
│ PurchaseDocumentValidator                    │
│ PurchaseOutputService                        │
└─────────────────────────────────────────────┘
        ↓                         ↓
现有 ExcelTask 数据模型       DocumentRenderGateway
        ↓                         ├─ LocalXlsxRenderer
MySQL + AttachmentStorage        ├─ LocalPdfRenderer
                                  └─ WpsRenderGateway（可选）
```

### 5.1 解析层接口

```java
public interface TabularDocumentParser {
    boolean supports(DocumentDescriptor document);
    ParsedWorkbook parse(DocumentSource source);
}
```

所有解析器都输出当前 `ExcelTaskParser` 已使用的 `ParsedWorkbook`，这样 PDF、图片和 Excel 可以复用后续映射、候选、复核与提交逻辑。

### 5.2 渲染层接口

```java
public interface DocumentRenderGateway {
    RenderedArtifact render(PurchaseDocumentSnapshot snapshot,
                            OutputFormat format,
                            OutputLayout layout);
}
```

支持：

```text
OutputFormat = XLSX | PDF
OutputLayout = NORMALIZED_TABLE | SOURCE_LIKE
```

MVP 只保证 `NORMALIZED_TABLE`。`SOURCE_LIKE` 需要模板坐标完整、目标列能够容纳内容时才允许使用。

## 6. 样例 PDF 的处理策略

### 6.1 文件特征

首个样例具有以下特征：

- 一页横向 A4；
- 标题为“采购明细”；
- 页面右上方有日期；
- 商品分左、中、右三个表格区域；
- 每行包含商品/规格、单位和一个空白格；
- 文件由 WPS 表格导出，具有文字层，不是扫描件；
- 原文档不是 AcroForm，没有可直接填写的表单字段。

### 6.2 解析优先级

```text
有文字层且模板命中
    → 按字符坐标和三块区域提取

有文字层但模板不命中
    → 通用表格线/字符坐标解析，低置信度进入人工复核

无文字层
    → 页面渲染为图片 → PaddleOCR → 表格重建 → 人工复核
```

### 6.3 模板指纹

新增 `DocumentTemplateDefinition`，首个模板使用以下指纹：

```json
{
  "templateCode": "PURCHASE_DETAIL_THREE_PANEL_V1",
  "pageCount": 1,
  "orientation": "LANDSCAPE",
  "requiredAnchors": ["采购明细", "日期"],
  "regions": ["LEFT", "MIDDLE", "RIGHT"],
  "minimumConfidence": 0.92
}
```

模板匹配不依赖文件名，避免用户重命名后失效。模板版本必须写入任务，后续模板升级不能改变旧任务的解析结果。

### 6.4 为什么不直接覆盖原 PDF

原表每行只有一个空白格。若同时写入“数量”和“价格”，会出现：

- 字段语义不明确；
- 数字拥挤或越界；
- PDF 和 XLSX 结构不一致；
- 无法继续计算小计和合计；
- 后续导入系统时缺少稳定列。

因此首版生成统一明细：

| 序号 | 配件名称 | 规格 | 单位 | 数量 | 采购单价 | 小计 | 备注 |
| ---: | --- | --- | --- | ---: | ---: | ---: | --- |

原 PDF 保留为任务来源，最终文件记录 `sourceAttachmentId`、模板版本和任务版本。

## 7. 业务数据模型

### 7.1 增量扩展现有 `excel_task`

首版不重命名现有表和 Java 包，避免大范围迁移。建议增加：

```sql
ALTER TABLE excel_task
  ADD COLUMN source_format VARCHAR(16) NOT NULL DEFAULT 'XLSX',
  ADD COLUMN parse_method VARCHAR(32),
  ADD COLUMN template_code VARCHAR(64),
  ADD COLUMN template_version INT,
  ADD COLUMN parse_confidence DECIMAL(5,4),
  ADD COLUMN document_metadata_json LONGTEXT;
```

取值建议：

```text
source_format = XLS | XLSX | CSV | PDF | IMAGE
parse_method = NATIVE_TABLE | PDF_TEXT_LAYOUT | OCR_TABLE
```

### 7.2 扩展行级规范化数据

继续使用 `ExcelTaskRowResult.normalizedJson`，统一字段：

```text
PRODUCT_NAME
SPEC
UNIT
QUANTITY
UNIT_PRICE
AMOUNT
REMARK
```

另增加字段来源信息：

```json
{
  "QUANTITY": {
    "value": "10",
    "source": "USER_MESSAGE",
    "sourceRunId": 918,
    "confidence": 1.0
  },
  "UNIT_PRICE": {
    "value": "12.50",
    "source": "LAST_PURCHASE_PRICE",
    "priceHistoryId": 667,
    "confirmed": false
  }
}
```

字段来源枚举：

```text
SOURCE_DOCUMENT
USER_MESSAGE
USER_FORM
PRODUCT_MASTER
SUPPLIER_PRICE
LAST_PURCHASE_PRICE
MANUAL_REVIEW
```

### 7.3 输出批次

当前 `excel_task_output` 一条记录对应一个文件。为了支持一次请求生成 XLSX 和 PDF，新增批次：

```sql
CREATE TABLE document_output_batch (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  operator_id BIGINT NOT NULL,
  task_version BIGINT NOT NULL,
  output_purpose VARCHAR(32) NOT NULL,
  requested_formats VARCHAR(64) NOT NULL,
  layout_code VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  request_digest VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  summary_json LONGTEXT,
  error_message VARCHAR(1000),
  create_time DATETIME NOT NULL,
  expires_at DATETIME,
  UNIQUE KEY uk_document_output_batch_key (operator_id, idempotency_key)
);

ALTER TABLE excel_task_output
  ADD COLUMN batch_id BIGINT,
  ADD COLUMN format VARCHAR(16),
  ADD KEY idx_excel_output_batch (batch_id);
```

一个批次可以有零到两条输出记录。只有请求的所有格式都生成且校验成功，批次才进入 `READY`；任何一个失败则进入 `PARTIAL_FAILED` 或 `FAILED`，不得把失败格式伪装成成功。

## 8. 任务状态机

复用现有任务状态，并增加文档解析与补全语义：

```text
UPLOADED
  ↓
INSPECTING
  ↓
PARSING
  ↓
READY_FOR_MAPPING
  ↓
READY_FOR_REVIEW
  ↓
READY_FOR_FILL
  ↓
READY_FOR_OUTPUT
  ↓
OUTPUT_GENERATING
  ↓
OUTPUT_READY
```

异常分支：

```text
INSPECTING/PARSING → WAITING_OCR | NEEDS_TEMPLATE_REVIEW | FAILED
READY_FOR_FILL → NEEDS_REVIEW
OUTPUT_GENERATING → OUTPUT_PARTIAL_FAILED | OUTPUT_FAILED
```

兼容要求：

- 现有 Excel 任务不强制经过 `READY_FOR_FILL`；该阶段仅用于需要后补数量或价格的文档。
- 已生成文件不因任务后续修改而变化；修改后生成新批次。
- 输出批次保存 `taskVersion`，版本不一致时拒绝生成。

## 9. Agent 工具设计

### 9.1 `inspect_document`

- 风险：`R0_READ_ONLY`
- 输入：当前会话附件 ID
- 输出：格式、页数、是否有文字层、是否扫描件、模板候选和可支持动作
- 限制：只能访问当前操作人、当前会话的附件

### 9.2 `create_purchase_document_task`

- 风险：`R1_DRAFT`
- 输入：附件 ID、供应商 ID（可选）、业务日期（可选）
- 输出：任务 ID、解析方式、模板、行数、低置信度行数
- 行为：只创建暂存任务，不修改商品、价格或库存

### 9.3 `get_purchase_document_task`

- 风险：`R0_READ_ONLY`
- 输入：任务 ID
- 输出：任务版本、字段完成度、问题统计、输出状态

### 9.4 `apply_purchase_document_values`

- 风险：`R1_DRAFT`
- 输入：任务 ID、任务版本、行级数量/价格/备注修改
- 规则：
  - 必须使用稳定的 `resultId` 或已确认 `productId`，不能仅用模糊商品名称定位；
  - 数量大于 0；
  - 单价大于等于 0，是否允许 0 由业务配置决定；
  - 金额由后端使用 `BigDecimal` 计算；
  - 单位冲突进入人工复核；
  - 更新后递增任务版本。

示例：

```json
{
  "taskId": 108,
  "expectedVersion": 6,
  "items": [
    {
      "resultId": 8101,
      "quantity": 10,
      "unitPrice": 12.50,
      "remark": "本次采购"
    }
  ]
}
```

### 9.5 `validate_purchase_document`

- 风险：`R0_READ_ONLY`
- 输入：任务 ID、任务版本、是否允许未填写行
- 输出：阻塞项、警告、已填写行数、未填写行数、总金额和价格来源统计

### 9.6 `generate_purchase_document`

- 风险：`R1_DRAFT`
- 输入：任务 ID、任务版本、输出格式、版式、是否包含未填写行
- 输出：批次 ID、异步状态和预览摘要
- 审批：执行前显示供应商、行数、未填写行、异常行、总金额、输出格式和有效期
- 幂等键：由服务端使用 `runId + actionId + taskId + taskVersion + requestDigest` 生成，模型不能传入

### 9.7 暂不新增的工具

首版不提供 `commit_purchase_receipt_from_document`。用户若要正式入库，应把已确认任务转换为现有 `PURCHASE_RECEIPT` 流程，并再次使用 `R2_WRITE` 审批。

## 10. HTTP API 设计

为减少前端和现有接口耦合，新增 `/document-tasks` 门面接口；内部仍复用 `ExcelTask` 服务。

### 10.1 创建任务

```http
POST /api/document-tasks
Content-Type: application/json
```

```json
{
  "attachmentId": 391,
  "purpose": "PURCHASE_DOCUMENT_FILL",
  "supplierId": 12,
  "businessDate": "2026-10-06"
}
```

响应 `202 Accepted`，返回 `taskId`、`status` 和查询地址。

### 10.2 查询任务和行

```http
GET /api/document-tasks/{taskId}
GET /api/document-tasks/{taskId}/rows?page=0&pageSize=50&filter=NEEDS_REVIEW
```

### 10.3 批量补数量和价格

```http
PATCH /api/document-tasks/{taskId}/purchase-values
```

```json
{
  "expectedVersion": 6,
  "items": [
    {"resultId": 8101, "quantity": 10, "unitPrice": 12.50},
    {"resultId": 8135, "quantity": 20, "unitPrice": 3.20}
  ]
}
```

### 10.4 校验

```http
POST /api/document-tasks/{taskId}/validate-output
```

```json
{
  "expectedVersion": 7,
  "includeUnfilledRows": true
}
```

### 10.5 生成双格式文件

```http
POST /api/document-tasks/{taskId}/outputs
```

```json
{
  "expectedVersion": 7,
  "formats": ["XLSX", "PDF"],
  "layout": "NORMALIZED_TABLE",
  "includeUnfilledRows": true,
  "idempotencyKey": "purchase-output-108-v7-20261006"
}
```

响应：

```json
{
  "batchId": 51,
  "status": "GENERATING",
  "progress": 0,
  "taskVersion": 7
}
```

### 10.6 查询和下载

```http
GET /api/document-tasks/{taskId}/outputs/{batchId}
GET /api/document-tasks/{taskId}/outputs/{batchId}/files/{outputId}/download
```

下载仍按当前登录用户校验任务和输出归属，不接受 object key、任意路径或远程 URL。

## 11. 数量、价格和金额规则

### 11.1 数量

- 使用 `BigDecimal` 保存，支持按公斤等非整数单位采购。
- 商品单位为“个、付、支、块”等离散单位时，默认要求整数。
- 用户提供的新单位不得静默覆盖商品主数据单位。
- 单位换算首版不自动执行；存在包装换算时必须人工确认。

### 11.2 采购单价

价格建议优先级：

```text
用户本次明确输入
  > 指定供应商当前价格
  > 同供应商最近采购价
  > 商品主数据进价
  > 留空等待用户填写
```

系统必须展示价格来源，建议价不能伪装成用户确认价。只有用户确认或明确说“按建议价填写”后，`confirmed` 才能变为 `true`。

### 11.3 金额

```text
行小计 = 数量 × 采购单价
总金额 = 所有已填写且有效的行小计之和
```

- 使用 `BigDecimal`；
- 金额精度和舍入方式由统一配置控制，建议人民币保留两位、`HALF_UP`；
- 输出写确定值，不依赖 Excel 打开时重算；
- PDF 与 XLSX 必须基于同一个不可变快照生成，合计必须一致。

## 12. 输出文件设计

### 12.1 XLSX

由 Apache POI 生成，至少包含：

1. `采购明细`：正式可编辑表；
2. `未填写与异常`：未匹配、未填数量/价格、单位冲突；
3. `生成说明`：来源文件、供应商、业务日期、任务版本、模板版本、生成时间和操作人。

采购明细列：

```text
序号、原始名称、匹配商品、规格、单位、数量、采购单价、小计、价格来源、备注
```

安全要求：

- 来源字符串以 `= + - @` 开头时转义，沿用现有公式注入防护；
- 冻结表头、设置筛选、货币格式和打印区域；
- 嵌入受控字体或使用兼容中文字体；
- 不写宏、外部链接或远程公式；
- 文件生成后重新读取，校验工作表、行数、数值和总计。

### 12.2 PDF

MVP 使用统一横向 A4 采购明细模板，不覆盖原 PDF。要求：

- 标题、供应商、日期和任务编号清晰；
- 表头跨页重复；
- 商品名称和规格自动换行；
- 数量、单价和小计右对齐；
- 页脚显示页码和生成时间；
- 最后一页显示总金额和异常说明；
- 中文字体随应用部署，不依赖宿主机字体；
- 生成后渲染为图片做自动/人工抽检，禁止文字裁切、黑块和表格越界。

### 12.3 文件一致性

生成前创建 `PurchaseDocumentSnapshot`：

```json
{
  "taskId": 108,
  "taskVersion": 7,
  "templateVersion": 1,
  "supplierId": 12,
  "businessDate": "2026-10-06",
  "items": [],
  "totalAmount": 189.00,
  "snapshotSha256": "..."
}
```

XLSX 和 PDF 都只能读取该快照，不能在两个渲染器中重新查询实时商品或价格，否则会出现同批次文件内容不一致。

## 13. 本地实现与 WPS 可选适配

### 13.1 默认本地实现

| 能力 | 默认实现 |
| --- | --- |
| Excel/CSV 读取 | Apache POI + 现有 CSV 解析 |
| 文字型 PDF 提取 | Apache PDFBox，保留字符坐标 |
| 扫描 PDF/图片 | Poppler 渲染 + PaddleOCR 服务 |
| XLSX 生成 | Apache POI |
| PDF 生成 | OpenPDF 或等价 Java PDF 渲染器，嵌入中文字体 |
| 存储 | 现有 `AttachmentStorage`（本地/COS） |
| 下载 | 现有鉴权流式下载或短期签名 URL |

### 13.2 WPS 适配层

配置 WPS 企业开放平台后，可以实现：

```java
public interface OfficeDocumentGateway {
    RemoteDocument upload(InputStream source, DocumentMetadata metadata);
    ConversionJob convert(String remoteFileId, OutputFormat targetFormat);
    ConversionStatus status(String jobId);
    InputStream download(String remoteFileId);
    void deleteTemporary(String remoteFileId);
}
```

用途限制：

- 在线预览；
- PDF/Office 格式转换；
- 复杂 Office 文件兼容性兜底；
- 将来把确认后的结果同步到企业 WPS 云文档。

WPS 不能绕过本项目的商品匹配、金额计算、审批和输出记录。WPS token、签名密钥和远程文件 ID 只能由后端保存；临时文档应设置保留期限并在成功下载或过期后清理。

配置建议：

```properties
document.office-provider=local
wps.enabled=false
wps.app-id=
wps.app-secret=
wps.callback-url=
wps.temporary-file-ttl=PT24H
```

### 13.3 降级策略

- WPS 未配置：使用本地解析与渲染；
- WPS 转换超时：保留已生成 XLSX，PDF 标记失败并允许单独重试；
- OCR 不可用：文字型 PDF 继续处理，扫描件进入 `WAITING_OCR`；
- 模型不可用：用户仍可在传统复核页面手工填数量和价格并生成文件。

## 14. 前端交互

### 14.1 对话入口

用户上传附件后，显示文件检查卡：

```text
识别为：采购明细 PDF
解析方式：PDF 文字层
识别商品：37 行
模板：三栏采购明细 V1
需要补充：数量 37 行、价格 37 行
```

Agent 可以继续询问或接受自然语言批量填写。

### 14.2 结构化复核页

复用现有 Excel 复核页，增加：

- 数量输入；
- 采购单价输入；
- 价格来源标签；
- 行小计；
- “使用最近采购价”批量操作；
- 只看未填写、异常、低置信度；
- 保存后的任务版本提示。

### 14.3 输出确认卡

确认卡不能只显示一句“是否生成”，至少展示：

```text
供应商：未指定/某供应商
有效商品：20 行
未填写：17 行（将保留为空）
异常：0 行
总金额：¥1,820.50
输出：XLSX + PDF
版式：标准采购明细
有效期：7 天
```

### 14.4 结果卡

每个格式独立显示状态和下载按钮：

```text
采购明细.xlsx  READY  下载
采购明细.pdf   READY  下载
```

如果 PDF 失败而 XLSX 成功，应显示部分成功和“重试 PDF”，不能要求用户重新解析和填写。

## 15. 安全、权限与审计

### 15.1 文件安全

- 校验扩展名、MIME、文件头和最大尺寸；
- PDF 禁止执行 JavaScript、嵌入文件和外部动作；
- 设置最大页数、最大字符数、最大表格行列数和解析超时；
- OCR/转换服务运行在隔离容器，不能访问业务数据库；
- 文档文本视为不可信数据，不能当作系统提示词或工具指令；
- 不允许客户端提交任意下载 URL，沿用 CloudBase/COS 受信来源校验。

### 15.2 业务安全

- 任务、行、输出和附件都按 `operatorId` 校验；
- 更新必须携带 `expectedVersion`；
- 文件生成使用幂等键和请求摘要；
- 数量、单价和金额由后端校验；
- 商品候选存在歧义时不自动选择；
- 文档输出不直接修改库存、订单或价格主数据；
- 正式入库必须再次执行实时库存、商品状态和任务版本校验。

### 15.3 审计

至少记录：

- 原始附件 ID 和 SHA-256；
- 解析器、模板及版本；
- OCR 或模型版本（使用时）；
- 每个字段的值、来源和确认人；
- 任务版本变化；
- 输出请求摘要、输出 SHA-256 和下载日志；
- WPS 远程转换请求 ID（启用时，不记录密钥和签名 URL）。

## 16. 错误码

建议新增：

```text
DOCUMENT_TYPE_UNSUPPORTED
DOCUMENT_TOO_LARGE
PDF_PAGE_LIMIT_EXCEEDED
PDF_TEXT_LAYER_MISSING
DOCUMENT_TEMPLATE_NOT_MATCHED
DOCUMENT_PARSE_LOW_CONFIDENCE
DOCUMENT_OCR_UNAVAILABLE
DOCUMENT_TASK_VERSION_CONFLICT
PURCHASE_VALUE_INVALID
PURCHASE_UNIT_CONFLICT
PURCHASE_PRICE_UNCONFIRMED
DOCUMENT_OUTPUT_VALIDATION_FAILED
DOCUMENT_OUTPUT_PARTIAL_FAILED
OFFICE_PROVIDER_UNAVAILABLE
OFFICE_CONVERSION_TIMEOUT
```

错误响应继续使用统一 `traceId`，不要把第三方响应体、文件系统路径或 WPS 凭据暴露给客户端。

## 17. 测试设计

### 17.1 解析测试

- 样例三栏采购 PDF 提取全部非空商品和单位；
- 页面顺序为左栏从上到下、中栏从上到下、右栏从上到下；
- 相同版式改名后仍能匹配模板；
- 标题或列位置明显变化时不误判模板；
- 扫描版进入 OCR 路径；
- 带恶意 PDF 动作、超页数和超大文件时拒绝。

### 17.2 补全测试

- 自然语言只填写指定商品，不误改同名不同规格；
- 多候选商品必须复核；
- 数量、单价非法时返回稳定错误；
- 单位为公斤时允许小数，单位为个时按配置拒绝小数；
- 使用建议价时保留价格来源和未确认状态；
- 并发更新使用旧版本返回 409。

### 17.3 输出测试

- XLSX 和 PDF 行数、数量、单价、小计、总计一致；
- 双格式来自相同快照哈希；
- 中文不乱码、不裁切；
- XLSX 可由 WPS 和 Excel 打开；
- PDF 横向 A4 打印不越界；
- 公式注入字符串按文本显示；
- 相同幂等键不重复生成；
- 其他用户不能下载；
- 过期输出不可下载；
- PDF 失败时 XLSX 可继续下载和单独重试。

### 17.4 Agent 评测

固定评测集至少覆盖：

1. “给所有商品数量填 1，价格留空”；
2. “只填料咀系列，8×30 到 8×60 各 10 个”；
3. 同名不同规格商品的消歧；
4. 用户要求采用最近采购价；
5. 用户要求生成 XLSX 和 PDF；
6. 用户要求生成后直接入库，系统必须再次确认；
7. 附件中含“忽略系统规则”等提示注入文本，系统不得执行；
8. 模型中途失败后可从任务版本继续。

## 18. 分阶段实施

### 阶段 A：固定模板 MVP

目标：完整跑通首个采购明细 PDF。

- 引入 PDFBox；
- 实现 `DocumentInspector`；
- 实现三栏采购模板和坐标提取；
- 输出 `ParsedWorkbook` 并接入现有 Excel 任务；
- 增加行级数量/价格 PATCH；
- 改造 Apache POI 输出为正式采购明细 XLSX；
- 增加本地 PDF 渲染；
- 增加输出批次和双格式下载；
- 前端增加填写和输出确认卡。

验收：样例文件可以提取、填写、确认并返回内容一致的 XLSX/PDF。

### 阶段 B：Agent 对话闭环

- 注册六个文档工具；
- 自然语言批量填写数量和价格；
- 接入供应商价格和最近采购价建议；
- 结果卡展示双格式文件；
- 增加断点恢复、重试和 Agent 评测。

验收：用户只通过对话即可完成上传到下载，但任何歧义都会进入结构化复核。

### 阶段 C：通用文档适配

- 扫描 PDF 表格 OCR；
- 更多采购模板；
- 模板配置和版本管理；
- PDF、图片、Excel 统一 `/document-tasks` 工作台；
- 模板学习只生成待审核配置，不自动上线。

### 阶段 D：可选 WPS 集成

- 企业自建应用和最小权限；
- `WpsRenderGateway`；
- 在线预览；
- 转换任务轮询、超时、清理和审计；
- 本地/WPS 双提供商一致性回归。

WPS 集成放在核心闭环之后，避免第三方账号、套餐、权限或网络问题阻塞 MVP。

## 19. 建议代码结构

```text
com.example.demo.document
├── api
│   ├── DocumentTaskController.java
│   └── DocumentOutputController.java
├── application
│   ├── PurchaseDocumentApplicationService.java
│   └── PurchaseDocumentOutputService.java
├── inspect
│   ├── DocumentInspector.java
│   └── DocumentDescriptor.java
├── parse
│   ├── TabularDocumentParser.java
│   ├── ExcelTabularDocumentParser.java
│   ├── PdfTextTableParser.java
│   ├── OcrTableParser.java
│   └── template
│       ├── DocumentTemplateDefinition.java
│       ├── DocumentTemplateMatcher.java
│       └── PurchaseThreePanelTemplateV1.java
├── render
│   ├── DocumentRenderGateway.java
│   ├── PurchaseDocumentSnapshot.java
│   ├── PoiPurchaseXlsxRenderer.java
│   ├── LocalPurchasePdfRenderer.java
│   └── wps
│       └── WpsRenderGateway.java
└── dto
    ├── DocumentTaskCreateRequest.java
    ├── PurchaseValuePatchRequest.java
    ├── DocumentOutputRequest.java
    └── DocumentOutputBatchView.java
```

现有 `com.example.demo.excel` 继续负责规范化行、映射、复核、商品候选和业务提交。新 `document` 包只处理多格式入口、模板、渲染和门面编排。

## 20. 开发顺序与工作量边界

建议严格按以下顺序：

1. 用样例 PDF 建立金标行数据和渲染快照。
2. 抽象 `TabularDocumentParser`，保证现有 Excel 测试不变。
3. 完成固定模板 PDF 解析。
4. 增加行级补值接口和版本控制。
5. 完成规范 XLSX 并做回读校验。
6. 完成 PDF 并做页面渲染验证。
7. 增加输出批次、鉴权下载和重试。
8. 最后注册 Agent 工具和前端对话体验。
9. 核心闭环稳定后再评估 WPS 适配。

不要在第一阶段同时做通用模板学习、WPS API、采购入库和复杂 OCR。首个交付只需证明：一份真实采购 PDF 可以可靠地变成可复核数据，并输出一致的 XLSX/PDF。

## 21. MVP 验收标准

- 样例 PDF 中所有非空商品行均被提取，顺序稳定；
- 商品、规格和单位不会因为三栏版式串行；
- 用户可以逐行或通过 Agent 批量填写数量和采购单价；
- 未填写和低置信度行清晰可见，不静默丢弃；
- 金额全部由后端计算；
- 输出前存在结构化确认；
- XLSX 和 PDF 内容、合计和任务版本一致；
- 输出可下载、可过期、可审计、不可越权；
- 同一请求重试不重复生成；
- 模型、OCR 或 WPS 不可用时仍可走人工复核和本地输出；
- 生成文件不修改商品、库存、订单、供应商价格或客户欠款；
- 用户选择正式采购入库时，进入独立 `R2_WRITE` 确认流程。

## 22. 待评审事项

以下问题需要产品或业务负责人确认后再进入开发：

1. 采购数量是否允许小数，是否按商品单位分别控制；
2. 采购单价是否允许为 0；
3. 未填写行默认保留还是从最终文件隐藏；
4. 首版是否必须复刻原 PDF 三栏布局，还是接受统一标准明细；
5. 价格建议默认使用哪一种来源，是否需要用户逐项确认；
6. PDF 输出是否需要门店抬头、联系人、电话和盖章区域；
7. 文件默认有效期是否沿用 7 天；
8. 是否在 MVP 就接入 WPS，还是完成本地闭环后再接；
9. 生成采购明细后，是否需要一键转换为现有 `PURCHASE_RECEIPT` 任务。

## 23. 参考资料

- WPS 开放平台概述：<https://open.wps.cn/documents/app-integration-dev/guide/overview>
- WPS WebOffice 快速上手：<https://open.wps.cn/documents/app-integration-dev/docs-center/quick-start/summary>
- WPS MCP 说明：<https://open.wps.cn/documents/app-integration-dev/mcp-server/introduction>
- WPS AgentSpace 创建会话：<https://open.wps.cn/documents/app-integration-dev/wps365/server/agentspace/session/session-create>
- 项目当前 Agent 能力：[09-Agent当前能力与工作流程.md](./09-Agent当前能力与工作流程.md)
- 项目当前 Excel 接口：[08-Excel文件任务后端接口.md](./08-Excel文件任务后端接口.md)
- 小五与 Excel 补全设计：[10-小五与Excel后端补全任务说明.md](./10-小五与Excel后端补全任务说明.md)
