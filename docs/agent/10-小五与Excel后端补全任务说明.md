# 恒迪五金小程序——“小五”与 Excel 后端补全任务说明

> 文档状态：后端设计修订稿（尚未全部实现）  
> 目标版本：下一轮前后端联调版本  
> 适用项目：`Hardware-Store-Management-System/springboot-demo`  
> 不在范围：外层 `Hardware-Store-Agent` 文件夹

## 0. 修订结论

本文是目标设计，不代表所列接口和字段已经存在。当前代码基线与目标之间的主要差距如下：

| 能力 | 当前实现 | 本设计目标 |
| --- | --- | --- |
| Agent 流式执行 | 模型 SSE + Run 增量持久化 + 小程序轮询 | 增加稳定阶段、进度、结构化结果和错误 |
| Agent 取消 | 数据库状态 + 本机 `Future.cancel(true)` | 增加取消时间、跨实例协作检查和可取消 HTTP 请求 |
| Agent 审批 | `approved` 布尔值 + 当前待确认工具 | 增加 `actionId`、独立动作版本和预览摘要校验 |
| Excel 上传 | 请求内同步解析，`PARSING` 几乎不可见 | 原文件入库后快速返回，后台解析并持久化进度 |
| Excel 状态 | 数据库字符串，状态转换散落在服务中 | Java 枚举 + 集中的状态转换服务 + 数据库条件更新 |
| 数据库升级 | 生产仍通过 `schema.sql` 初始化 | 引入版本化迁移，已有环境可安全升级和回滚代码 |
| 输出文件 | 没有输出记录和安全读取接口 | 受控存储、输出元数据、鉴权下载和过期策略 |

本轮采用以下确定方案：

1. **数据库是真实状态源。** 本机 Future、内存进度和消息通知只能用于加速，不能决定任务最终状态。
2. **Run 状态与处理阶段分离。** `status` 表示生命周期，`stageCode` 表示当前在做什么，前端不得混用。
3. **进度不造假。** 总量未知时 `percent` 返回 `null`；阶段切换后重新计算阶段内进度，不把模型思考时间伪装成百分比。
4. **审批使用独立动作版本。** 不使用会被进度更新频繁改变的实体 `version` 作为审批版本。
5. **Excel 后台任务首版采用数据库租约。** 不强依赖 Redis 或消息队列；部署多实例时由数据库抢占、心跳和取消标记保证唯一执行。
6. **正式批量写入不支持中途取消或部分成功。** 取消只能发生在解析、匹配、校验、报价和比较等非写入阶段。
7. **兼容旧接口一个联调版本。** 旧字段保留，新字段只追加；新 Excel 写入工具从第一天起只接受新版审批契约。

### 0.1 分阶段交付边界

- **P0-A（协议与迁移基础）**：状态枚举、进度/结果/错误 DTO、数据库迁移、Agent 审批版本。
- **P0-B（可恢复后台任务）**：Excel 异步解析、数据库租约、取消、重试、断线恢复。
- **P0-C（Agent 接入 Excel）**：查询进度、审核摘要、校验和正式提交工具。
- **P1-A（文件输出）**：买家报价、输出文件表、安全下载。
- **P1-B（经营数据增强）**：供应商价格历史、Excel 版本比较。
- **P2（读取模型治理）**：列表 DTO 与分页兼容升级。

P0-A、P0-B、P0-C 分别验收，不把整份文档作为一次发布的大事务。

## 1. 任务背景

恒迪五金小程序面向 50 岁以上、熟悉微信但不熟悉复杂经营软件的用户。新版前端将“小五”从普通聊天页调整为任务型经营助手，并补全了 Excel 上传、映射、审核、确认、结果查看等界面。

后端已经具备 Agent 会话、附件、审批、取消，以及 Excel 上传、映射、逐行审核、提交校验和幂等写入等基础能力。本任务不重写现有模块，重点补齐以下闭环：

1. 让前端能够准确展示 Agent 和 Excel 任务的当前阶段、完成数量和异常数量。
2. 让长任务可以后台继续、取消、断线后恢复查看。
3. 让危险操作在执行前提供稳定、结构化、可校验的影响预览。
4. 让处理结果不再只有聊天文本，而是能够显示成功、跳过、失败、异常和后续业务入口。
5. 打通“小五”与真实 Excel 任务，而不是让模型只阅读附件文本。
6. 补齐买家报价文件、厂家价格历史和 Excel 版本对比能力。

## 2. 设计原则

### 2.1 必须遵守

- 复用现有 Spring Boot、JPA、认证、`ApiResponse`、Agent Harness 和 Excel 暂存确认模型。
- 现有正式写入继续使用事务、乐观锁和幂等键。
- 商品、进价、库存、订单状态等写操作必须先预览、再明确确认。
- 不允许模型通过“好的”“继续”等自然语言直接绕过审批。
- 后端返回业务状态与结构化数据，前端不应解析自然语言来判断任务阶段。
- 所有查询、取消、确认、下载必须校验当前操作人。
- 后端不得返回任意小程序页面路径；业务跳转使用双方约定的目标枚举。
- 错误响应必须包含稳定错误码，不以 Java 异常类名或异常原文作为前端判断条件。

### 2.2 明确不做

- 不迁移技术框架。
- 不删除已有 Agent、Excel、商品、库存或订单接口。
- 不把简单商品查询、库存查询强制改成 Agent 操作。
- 不允许库存、进价等批量写入出现不可解释的部分提交。
- 本任务不修改外层 `Hardware-Store-Agent` 项目。

## 3. 当前能力复用清单

以下能力已经存在，应直接复用并保持兼容：

### 3.1 Agent

- `POST /agent/messages`
- `POST /agent/messages/stream`
- `GET /agent/runs/{runId}`
- `POST /agent/runs/{runId}/approval`
- `POST /agent/runs/{runId}/cancel`
- Agent 会话列表、详情、改名、删除
- 附件上传、云文件登记、附件解析
- `R0_READ_ONLY`、`R1_DRAFT`、`R2_WRITE` 风险分级

### 3.2 Excel

- `POST /excel-tasks`
- `POST /excel-tasks/cloud`
- `GET /excel-tasks`
- `GET /excel-tasks/{taskId}`
- 原始行分页
- 字段映射
- 审核汇总与审核行分页
- 单行人工审核
- 提交前校验
- 幂等正式提交

### 3.3 已有安全机制

- Excel `expectedVersion` 乐观锁
- Excel `idempotencyKey` 幂等提交
- 提交前商品价格、库存和商品资料漂移检查
- Agent 操作人校验
- Agent 取消状态持久化

## 4. P0：Agent 任务状态结构化

### 4.1 扩展运行结果

现有 `AgentRunResult` 保留原字段，并新增以下结构：

```json
{
  "runId": 1024,
  "conversationId": 81,
  "status": "RUNNING",
  "output": "",
  "progress": {
    "stageCode": "MATCHING_PRODUCTS",
    "stageText": "正在匹配商品",
    "completed": 183,
    "total": 286,
    "percent": 64,
    "issueCount": 3,
    "canCancel": true,
    "canLeave": true,
    "updatedAt": "2026-10-05T15:20:18"
  },
  "summary": null,
  "actions": [],
  "error": null,
  "retry": {
    "supported": false,
    "mode": null
  },
  "pendingAction": null
}
```

### 4.2 阶段枚举

首版至少支持：

```text
QUEUED
READING_ATTACHMENT
READING_FILE
ANALYZING_REQUEST
MATCHING_PRODUCTS
CHECKING_INVENTORY
BUILDING_PREVIEW
WAITING_CONFIRMATION
EXECUTING
GENERATING_RESULT
COMPLETED
PARTIAL
FAILED
CANCELLED
EXPIRED
```

`stageText` 由后端根据 `stageCode` 生成用户可读中文，不由模型自由生成。

阶段码与 Run 状态的职责必须分开：

- `status=RUNNING` 时可处于 `QUEUED` 到 `GENERATING_RESULT` 任一执行阶段。
- `status=WAITING_APPROVAL` 时阶段固定为 `WAITING_CONFIRMATION`。
- 终态 `COMPLETED/PARTIAL/FAILED/CANCELLED/EXPIRED` 的阶段分别映射为同名终态阶段；不得出现“状态已取消但阶段仍在执行”的组合。
- `completed/total` 是当前阶段的工作量，不跨不同计量单位累加。读取文件按行，模型调用按步骤，无法计数的阶段两者均为 `null`。
- `percent` 仅在 `total > 0` 时计算并限制为 `0..100`，同一阶段内不得倒退。
- `issueCount` 是已发现且尚未解决的问题数量，不等同于失败数量。
- `updatedAt` 每次阶段、计数、取消标记或终态变化时更新，用于前端判断数据是否新鲜。

### 4.3 数据库存储

给 `agent_run` 增加：

```sql
stage_code VARCHAR(50),
stage_text VARCHAR(120),
progress_completed INT,
progress_total INT,
issue_count INT NOT NULL DEFAULT 0,
result_json LONGTEXT,
updated_at DATETIME,
cancel_requested_at DATETIME,
pending_action_version BIGINT NOT NULL DEFAULT 0,
pending_preview_digest VARCHAR(64),
worker_id VARCHAR(100),
lease_until DATETIME,
heartbeat_at DATETIME,
attempt_count INT NOT NULL DEFAULT 0
```

`stage_text` 可以由 `stage_code` 映射生成，但首版仍持久化，便于历史 Run 按当时文案恢复。`result_json` 只保存受控 DTO，不保存任意模型 JSON。进度更新使用条件 `UPDATE`，不得顺带递增 `pending_action_version`。

`updated_at` 对旧数据迁移时回填 `COALESCE(completed_at, started_at)`；数值进度允许为空，避免把未知进度写成 0%。

### 4.4 状态枚举

在现有状态基础上补充：

```text
PARTIAL
```

推荐终态：

```text
COMPLETED
PARTIAL
FAILED
CANCELLED
EXPIRED
```

`PARTIAL` 只允许用于没有正式业务写入的任务。只要进入商品价格、库存、商品资料或订单的事务写入，结果只能整体 `COMPLETED` 或整体 `FAILED`。

### 4.5 Agent 崩溃恢复边界

仅持久化进度不足以实现应用重启恢复。Agent 执行也使用数据库租约：开始执行时写入 `worker_id/lease_until/heartbeat_at`，等待审批和终态时释放租约。恢复器只接管租约过期、未取消且仍为 `RUNNING` 的 Run。

恢复时从已持久化的会话上下文和工具结果继续：

- 尚未得到模型响应的步骤可以重新请求模型。
- R0 查询工具允许按相同参数重放。
- R1/R2 工具只有在审批令牌未消费时才能继续；已成功写入的工具必须先通过幂等记录恢复结果，禁止再次执行。
- 无法证明写入结果的 Run 转为 `FAILED` 并要求人工核对，不能自动猜测成功或失败。

首个实现版本可以只做到“断线恢复查看、实例不崩溃时后台继续”；只有租约恢复器和上述幂等恢复测试完成后，才能宣称“应用重启后继续执行”。

## 5. P0：Agent 危险操作审批契约

### 5.1 统一影响预览

禁止每个工具随意返回不同字段后让前端猜测。`pendingAction` 统一为：

```json
{
  "actionId": "tool-call-123",
  "toolName": "commit_excel_task",
  "risk": "R2_WRITE",
  "title": "确认更新商品进价",
  "description": "将根据厂家价格表更新商品成本",
  "impact": {
    "affectedCount": 18,
    "exceptionCount": 2,
    "willChange": [
      "18个商品的进价"
    ],
    "willNotChange": [
      "零售价",
      "批发价",
      "库存数量"
    ],
    "warnings": [
      {
        "code": "PRICE_CHANGE_OVER_20_PERCENT",
        "message": "2个商品涨幅超过20%",
        "count": 2
      }
    ],
    "items": []
  },
  "expiresAt": "2026-10-05T16:30:00",
  "actionVersion": 4,
  "previewDigest": "sha256:..."
}
```

`impact` 是统一外壳，各工具只填充受控字段。`items` 必须分页或截断，并同时返回 `itemCount`、`itemsTruncated`；不得把数千行预览塞进 Agent Run 响应。

### 5.2 审批请求

保持旧的 `approved` 入参兼容一版，同时支持新结构：

```json
{
  "decision": "APPROVE",
  "actionId": "tool-call-123",
  "actionVersion": 4,
  "previewDigest": "sha256:..."
}
```

`decision` 只允许：

```text
APPROVE
REJECT
```

服务端确认时必须同时校验：

- 当前操作人
- `runId`
- `actionId`
- 当前状态仍为 `WAITING_APPROVAL`
- 审批尚未过期
- `actionVersion`
- `previewDigest`
- 待执行参数与生成预览时一致

服务端生成预览时，对规范化后的 `toolName + arguments + impact` 计算 SHA-256，保存到 `pending_preview_digest`。确认时重新计算并比较，避免预览与执行参数发生漂移。

审批抢占必须使用单条条件更新：`WAITING_APPROVAL -> RUNNING`，条件同时包含 `runId`、`operatorId`、`actionId`、`actionVersion`、未过期和 `cancel_requested_at IS NULL`。只有抢占成功的请求可以执行工具。

### 5.3 旧审批接口兼容规则

- 旧请求 `{ "approved": true|false }` 保留一个联调版本，只服务已经存在的非 Excel 工具。
- 新响应始终返回 `actionId`、`actionVersion` 和 `previewDigest`，前端应优先发送新结构。
- `commit_excel_task` 等新增 R2 工具不接受旧审批结构。
- 兼容期结束后删除布尔审批入口前，必须先统计旧客户端调用量并发布明确版本门槛。

## 6. P0：Agent 结构化结果和业务跳转

### 6.1 结果模型

```json
{
  "summary": {
    "successCount": 18,
    "skippedCount": 3,
    "failedCount": 2,
    "warningCount": 2
  },
  "failedItems": [
    {
      "sourceRow": 26,
      "code": "PRODUCT_NOT_FOUND",
      "message": "没有找到对应商品"
    }
  ],
  "outputFiles": [
    {
      "id": 92,
      "name": "恒达建材报价单.xlsx",
      "mimeType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "size": 24832,
      "expiresAt": "2026-10-06T15:20:18"
    }
  ],
  "actions": [
    {
      "type": "OPEN_BUSINESS_PAGE",
      "label": "查看受影响商品",
      "target": "PRODUCT_LIST",
      "params": {
        "taskId": 108
      }
    }
  ],
  "retry": {
    "supported": true,
    "mode": "FAILED_ONLY"
  }
}
```

### 6.2 跳转目标白名单

首版支持：

```text
WORKBENCH
PRODUCT_LIST
PRODUCT_DETAIL
INVENTORY_LIST
SALES_ORDER_DETAIL
PURCHASE_ORDER_DETAIL
EXCEL_TASK_DETAIL
PRICE_HISTORY
```

后端只返回业务目标和参数，不返回 `/pages/...` 等客户端路径。

每个 `target` 必须有独立参数白名单：例如 `PRODUCT_DETAIL` 只接受 `productId`，`EXCEL_TASK_DETAIL` 只接受 `taskId`。服务端构建 Action 时完成类型校验，前端仍需忽略未知目标。`label` 是展示文案，不参与路由判断。

`result_json` 默认上限 256 KB，只保存摘要、失败项前 100 条、输出文件元数据和业务动作。完整失败清单通过分页接口读取，避免 Run 记录无限增长。

## 7. P0：Excel 上传改为异步任务

### 7.1 当前问题

目前上传请求内部同步解析整个文件，导致 `PARSING` 状态对前端几乎不可见，较大文件还可能触发请求超时。

### 7.2 目标流程

1. 校验文件名、类型、大小。
2. 把原始文件复制到服务端控制的 `AttachmentStorage`。CloudBase 临时下载地址不得作为后台任务的数据源。
3. 在短事务中创建 `UPLOADED` 状态任务并提交。
4. 事务提交后投递解析任务，返回 `202 Accepted` 和任务详情。
5. 后台解析工作表、列和数据行。
6. 持续更新进度。
7. 成功进入 `READY_FOR_MAPPING`，失败进入 `FAILED`。

控制器返回 `ResponseEntity<ApiResponse<ExcelTaskDetail>>` 并明确使用 HTTP 202；`ApiResponse.success` 仍为 `true`。收到 202 只表示任务已受理，不表示解析完成。

现有 `ExcelTaskService` 上的类级 `@Transactional` 不适合包住后台解析。实现时拆为：

- `ExcelTaskCommandService`：校验、保存原文件、创建任务；短事务。
- `ExcelTaskDispatcher`：在事务提交后投递任务；不得在提交前让工作线程读取任务。
- `ExcelTaskWorker`：抢占租约、解析、分批保存工作表和行；每批独立事务。
- `ExcelTaskProgressService`：使用条件更新维护阶段、计数、心跳和取消标记。

解析器可以先在内存中读取工作簿结构，但写库必须分批，建议每批 200～500 行。失败或重试前删除该任务尚未完成的一次解析产物，不能把两次尝试的工作表和行混合。

任务详情增加：

```json
{
  "progress": {
    "stageCode": "READING_FILE",
    "stageText": "正在读取文件",
    "completedRows": 120,
    "totalRows": 286,
    "percent": 42
  },
  "canCancel": true,
  "canRetry": false
}
```

### 7.3 新增接口

```http
POST /excel-tasks/{taskId}/cancel
POST /excel-tasks/{taskId}/retry
```

取消只允许发生在非写入阶段。正式提交保持短事务、不可中途取消。

接口语义：

- `cancel`：幂等。已经 `CANCELLED` 再次调用仍返回当前任务；其他终态返回稳定错误 `EXCEL_TASK_ALREADY_FINISHED`。
- `retry`：请求必须带 `expectedVersion`。仅 `FAILED` 和 `CANCELLED` 可重试，并根据 `failedStageCode` 回到 `PARSING` 或 `MATCHING`。
- 同一任务同一时间最多存在一个有效租约；重复投递不得并发解析。

### 7.4 Excel 状态机

统一使用枚举，避免继续使用散落字符串：

```text
UPLOADED
PARSING
READY_FOR_MAPPING
MATCHING
READY_FOR_REVIEW
VALIDATING
READY_TO_COMMIT
COMMITTING
COMMITTED
PARTIAL
FAILED
CANCELLED
```

允许的主要流转：

```text
UPLOADED -> PARSING -> READY_FOR_MAPPING
READY_FOR_MAPPING -> MATCHING -> READY_FOR_REVIEW
READY_FOR_REVIEW -> VALIDATING -> READY_TO_COMMIT
READY_TO_COMMIT -> COMMITTING -> COMMITTED
UPLOADED/PARSING/READY_FOR_MAPPING/MATCHING/READY_FOR_REVIEW/VALIDATING -> FAILED
UPLOADED/PARSING/READY_FOR_MAPPING/MATCHING/READY_FOR_REVIEW/VALIDATING/READY_TO_COMMIT -> CANCELLED
FAILED -> PARSING 或 MATCHING（按失败阶段重试）
```

`COMMITTING` 不可取消；它必须在同一个正式业务事务内进入并离开。事务回滚后任务恢复为 `READY_TO_COMMIT` 并记录稳定错误，不允许留下永久 `COMMITTING`。`PARTIAL` 只用于报价、比较等非写入衍生任务，Excel 正式提交本身不进入 `PARTIAL`。

### 7.5 数据库租约与崩溃恢复

`excel_task` 增加以下后台执行字段：

```sql
stage_code VARCHAR(50),
progress_completed INT,
progress_total INT,
issue_count INT NOT NULL DEFAULT 0,
cancel_requested_at DATETIME,
failed_stage_code VARCHAR(50),
attempt_count INT NOT NULL DEFAULT 0,
worker_id VARCHAR(100),
lease_until DATETIME,
heartbeat_at DATETIME,
business_context_json LONGTEXT
```

工作节点通过条件更新抢占任务：只有状态允许且 `lease_until IS NULL OR lease_until < now` 才能写入自己的 `worker_id` 和新租约。运行期间定时续租，并在每批数据、每个阶段以及任何外部调用前后检查 `cancel_requested_at`。实例崩溃后，其他实例可以在租约过期后继续或按失败阶段重试。

## 8. P0：小五接入真实 Excel 工作流

新增以下 Agent 内部工具或等价应用服务。工具不要求直接公开为 HTTP 接口，但必须复用 Excel 服务层，不得复制业务逻辑。

```text
create_excel_task_from_attachment
get_excel_task_progress
get_excel_review_summary
validate_excel_task
commit_excel_task
generate_quote_file
compare_excel_versions
```

风险等级：

| 工具 | 风险等级 | 是否审批 |
| --- | --- | --- |
| 创建文件任务 | R1_DRAFT | 否 |
| 查询任务进度 | R0_READ_ONLY | 否 |
| 获取审核摘要 | R0_READ_ONLY | 否 |
| 校验文件任务 | R0_READ_ONLY | 否 |
| 生成报价文件 | R1_DRAFT | 建议否 |
| 比较文件版本 | R1_DRAFT | 否 |
| 正式提交商品/价格/库存 | R2_WRITE | 必须 |

`commit_excel_task` 必须复用现有 `expectedVersion`、幂等键和正式提交服务。

当前 `AgentPolicyEngine` 会让所有 `R1_DRAFT` 强制审批，因此上表中的“R1 但不审批”尚不能直接实现。修订方案是在工具定义中增加受控的 `approvalMode=AUTO|REQUIRE_CONFIRMATION|DENY`，风险等级仍表示业务影响；策略引擎只允许以下组合：

- `R0_READ_ONLY + AUTO`
- `R1_DRAFT + AUTO`：仅限用户本人可删除的技术性产物，如解析任务、报价文件、比较结果
- `R1_DRAFT/R2_WRITE + REQUIRE_CONFIRMATION`
- `R3_SENSITIVE/R4_RESTRICTED + DENY`

不能由模型指定 `approvalMode`。新增工具注册时必须通过启动期校验，不在允许组合内则启动失败。

Agent Excel 工具只做应用服务适配：

- 所有工具入参中的 `taskId` 必须在服务层按 `operatorId` 重新查询。
- `create_excel_task_from_attachment` 只能使用当前会话、当前操作人的已上传附件 ID，不接受 URL 或 object key。
- `commit_excel_task` 的幂等键由服务端按 `runId + actionId + taskId + taskVersion` 生成，模型不能传入。
- 工具预览生成后保存任务版本和业务数据摘要；执行前再次调用 Excel 校验服务。
- Agent Run 被取消时，只取消尚未进入 `COMMITTING` 的 Excel 任务；不能用取消 Run 回滚已经提交的业务事务。

## 9. P1：买家报价文件

当前 `BUYER_QUOTE` 只支持审核预览，需要增加独立的文件生成语义，不与业务写入 `commit` 混用。

### 9.1 新增接口

```http
POST /excel-tasks/{taskId}/generate-quote
GET  /excel-tasks/{taskId}/outputs
GET  /excel-tasks/{taskId}/outputs/{outputId}/download
```

### 9.2 报价生成请求

```json
{
  "expectedVersion": 6,
  "pricingMode": "CUSTOMER_TYPE_PRICE",
  "customerId": 32,
  "includeUnmatchedRows": true,
  "idempotencyKey": "quote-108-6-20261005"
}
```

### 9.3 报价结果要求

- 保留客户原始商品名称和原始行号。
- 输出匹配后的商品、规格、单位、数量、报价单价和小计。
- 未匹配行单独标明原因，不能静默删除。
- 文件生成结果记录操作人、任务、客户、定价模式和时间。
- 相同幂等键不得重复生成多份相同文件。

## 10. P1：输出文件存储与下载

现有 `AttachmentStorage` 只有保存和删除能力，需要增加安全读取能力：

```java
StoredObjectMetadata stat(String objectKey);
InputStream openStream(String objectKey);
String createTemporaryDownloadUrl(String objectKey, Duration ttl);
```

不得用 `byte[] get(...)` 读取大文件，避免下载时把完整文件放进堆内存。实现可以二选一：生产 COS 返回短时签名 URL；本地存储由鉴权控制器使用流式响应。两种方式都必须先通过输出文件 ID 完成归属校验。

新增 `excel_task_output` 表：

```sql
id BIGINT PRIMARY KEY AUTO_INCREMENT,
task_id BIGINT NOT NULL,
operator_id BIGINT NOT NULL,
output_type VARCHAR(32) NOT NULL,
object_key VARCHAR(500) NOT NULL,
original_name VARCHAR(255) NOT NULL,
mime_type VARCHAR(100) NOT NULL,
file_size BIGINT NOT NULL,
sha256 VARCHAR(64) NOT NULL,
status VARCHAR(32) NOT NULL,
error_message VARCHAR(1000),
create_time DATETIME NOT NULL,
expires_at DATETIME
```

下载要求：

- 根据输出文件 ID 查 object key，不接受客户端直接传 object key。
- 校验任务归属和当前操作人。
- 使用短时效签名 URL，或由后端鉴权后流式输出。
- 正确设置文件名、MIME 和长度。
- 记录下载日志。

输出状态使用 `GENERATING/READY/FAILED/EXPIRED`。先创建 `GENERATING` 记录，文件写入成功并校验大小、SHA-256 后才切换 `READY`；数据库事务失败时清理孤立对象，存储删除失败则记录告警并由定时清理任务回收。

`GET /outputs/{outputId}/download` 不直接把签名 URL 永久写入数据库。每次请求校验后动态生成，默认有效期 10 分钟。

## 11. P1：厂家价格同步与价格历史

### 11.1 Excel 任务业务上下文

创建 `SUPPLIER_PRICE` 任务时增加：

```json
{
  "purpose": "SUPPLIER_PRICE",
  "businessContext": {
    "supplierId": 12,
    "priceEffectiveDate": "2026-10-05"
  }
}
```

建议存储为独立字段或 `business_context_json`，但 `supplierId` 必须经过权限和存在性校验。

修订决定：首版使用 `business_context_json` 保持任务表稳定，同时在创建请求中使用强类型 DTO。`SUPPLIER_PRICE` 必须提供有效 `supplierId`；`priceEffectiveDate` 为空时由服务端取门店时区当天日期，并把最终值写入上下文，不能在提交时重新计算。

### 11.2 新增价格历史表

```sql
CREATE TABLE product_price_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  supplier_id BIGINT,
  price_type VARCHAR(32) NOT NULL,
  before_price DECIMAL(12,2),
  after_price DECIMAL(12,2) NOT NULL,
  change_percent DECIMAL(9,4),
  source_type VARCHAR(32) NOT NULL,
  source_task_id BIGINT,
  operator_id BIGINT NOT NULL,
  effective_date DATE,
  create_time DATETIME NOT NULL,
  KEY idx_price_history_product_time (product_id, create_time),
  KEY idx_price_history_supplier_time (supplier_id, create_time)
);
```

更新进价时在同一事务中：

1. 写入价格历史。
2. 更新商品进价。
3. 写入操作日志。
4. 返回涨价、降价、未变化、超过阈值数量。

只有价格实际变化时写历史。`before_price` 为空表示此前没有进价；此时 `change_percent` 为空，不计入“涨幅超过阈值”。所有金额按商品字段现有精度归一化后比较，禁止用 `double` 计算百分比。

前端所需摘要：

```json
{
  "increasedCount": 8,
  "decreasedCount": 6,
  "unchangedCount": 4,
  "overThresholdCount": 2,
  "thresholdPercent": 20
}
```

## 12. P1：Excel 版本比较

建立独立比较任务：

```http
POST /excel-comparisons
GET  /excel-comparisons/{comparisonId}
POST /excel-comparisons/{comparisonId}/cancel
```

请求示例：

```json
{
  "baseTaskId": 101,
  "newTaskId": 108,
  "matchKeys": ["BARCODE", "SPEC"]
}
```

结果至少包含：

```json
{
  "status": "COMPLETED",
  "summary": {
    "added": 12,
    "removed": 3,
    "changed": 28,
    "unchanged": 243
  },
  "changes": [
    {
      "productId": 26,
      "productName": "304不锈钢六角螺丝",
      "field": "costPrice",
      "before": 0.32,
      "after": 0.38,
      "changePercent": 18.75
    }
  ]
}
```

比较结果必须可分页，避免把大量差异一次返回。

比较任务创建前校验两个源任务都属于当前操作人、都已完成解析且用途兼容。首版默认键为 `BARCODE`；用户可以显式增加 `SPEC`。任一侧出现空键或重复键时，该行进入问题列表，不得任意配对。比较结果写独立明细表并按 `comparison_id + change_type + id` 分页，排序必须稳定。

## 13. P1：统一错误与重试协议

在 `ApiResponse` 中增加可选的 `error` 字段：

```json
{
  "success": false,
  "message": "文件任务已被其他操作更新，请刷新后重试",
  "error": {
    "code": "EXCEL_TASK_VERSION_CONFLICT",
    "userMessage": "任务内容已经变化，请刷新后重新确认",
    "retryable": true,
    "retryAfterMs": 0,
    "traceId": "4d6b..."
  },
  "timestamp": "2026-10-05T15:20:18"
}
```

首版错误码：

```text
NETWORK_TEMPORARY_ERROR
AGENT_MODEL_UNAVAILABLE
AGENT_RUN_EXPIRED
AGENT_RUN_ALREADY_FINISHED
AGENT_ACTION_VERSION_CONFLICT
EXCEL_PARSE_FAILED
EXCEL_TASK_VERSION_CONFLICT
EXCEL_HAS_UNREVIEWED_ROWS
BUSINESS_DATA_CHANGED
EXCEL_TASK_ALREADY_FINISHED
EXCEL_TASK_CANCELLED
FILE_TOO_LARGE
FILE_TYPE_NOT_SUPPORTED
OUTPUT_FILE_NOT_FOUND
OUTPUT_FILE_EXPIRED
```

服务端日志保留完整异常，客户端只接收安全、可理解的用户文案和 `traceId`。

当前 `GlobalExceptionHandler` 会把部分异常原文直接返回，实施时新增 `BusinessException(code, httpStatus, userMessage, retryable, retryAfterMs)`，业务服务不得再让前端依赖 `IllegalArgumentException` 文案。兼容期间 `message` 等于 `error.userMessage`，旧前端仍能展示。

HTTP 状态约定：

| 场景 | HTTP 状态 |
| --- | --- |
| 参数、文件类型或状态不允许 | 400 |
| 未登录 | 401 |
| 已登录但无权访问 | 404（避免泄露资源存在性） |
| 版本、幂等键或状态竞争 | 409 |
| 文件过大 | 413 |
| 模型或下游暂时不可用 | 503 |
| 异步任务创建成功 | 202 |

`traceId` 由请求过滤器生成或透传合法的调用链 ID，写入日志 MDC 和响应；不得使用数据库主键、用户标识或异常类名代替。

## 14. P1：取消机制的多实例加固

现有取消机制使用数据库状态加本机 `Future.cancel(true)`。单实例可用，多实例下取消请求可能落到不同节点。

必须补充：

- 每次模型调用前后重新检查数据库任务状态。
- 每次工具执行前后重新检查数据库任务状态。
- R2 写工具执行前必须确认状态仍为 `RUNNING` 且审批令牌有效。
- 模型 HTTP 请求支持主动取消或连接中断。
- 持久化 `cancel_requested_at`。
- 多实例部署时通过 Redis、消息队列或数据库事件通知实际执行节点。
- 已经进入终态的任务重复取消应保持幂等，不返回不可恢复的未知错误。

### 14.1 首版落地方案

首版不引入 Redis 或消息队列，以数据库状态和租约作为跨实例协调机制：

1. 取消接口原子写入 `cancel_requested_at`，并把仍可取消的状态切换为 `CANCELLED`。
2. 本机存在 Future 时同时 `cancel(true)`，这只是降低资源消耗的优化。
3. 执行节点在模型调用、工具准备、工具执行、批次落库和阶段切换前后重新读取数据库状态。
4. 所有阶段更新和最终结果保存都带允许的前置状态条件；更新行数为 0 时重新读取终态，不覆盖取消结果。
5. R2 工具在事务开始前使用条件更新抢占一次性审批令牌；事务开始后不再允许取消。
6. 定时恢复器只接管租约过期且未取消的任务。

### 14.2 模型 HTTP 主动取消

现有阻塞式 `SimpleClientHttpRequestFactory` 的线程中断不保证立即关闭网络连接。改造模型网关时使用支持取消句柄的异步 HTTP 客户端，并把请求句柄登记到 `runId`；取消时同时取消 HTTP 请求、关闭响应流。即便下游不响应取消，数据库状态检查和条件保存仍保证它不能继续执行工具或覆盖终态。

多实例通知可在后续增加 Redis Pub/Sub，但它只能缩短实际执行节点收到取消的时间，不能替代数据库校验。

## 15. P2：业务列表读取 DTO 与分页

库存、销售单和采购单列表不应长期直接返回持久化实体。新增面向小程序的读取 DTO。

库存示例：

```json
{
  "id": 28,
  "productId": 102,
  "productName": "304不锈钢六角螺丝",
  "barcode": "6900000000012",
  "spec": "M6×30",
  "unit": "盒",
  "quantity": 8,
  "warningThreshold": 10,
  "warning": true,
  "locationName": "A区-03",
  "lastUpdateTime": "2026-10-05T14:20:00"
}
```

订单 DTO 增加：

- `customerName` 或 `supplierName`
- `operatorName`
- `itemCount`
- `primaryProductNames`
- `canStockIn` / `canStockOut`
- `canRegisterPayment`

商品、库存、客户、供应商、销售单和采购单列表逐步改为统一分页结构：

```json
{
  "page": 0,
  "size": 20,
  "totalElements": 286,
  "totalPages": 15,
  "items": []
}
```

旧列表接口可暂时兼容，避免一次性破坏现有前端。

## 16. 事务和“部分成功”规则

### 16.1 允许部分成功

- 文件解析
- 商品匹配
- 报价生成
- 版本比较
- 非写入型批处理

上述任务可以返回 `PARTIAL`，并提供失败项和“只重试失败项”。

### 16.2 不允许部分写入

- 批量修改商品进价
- 批量调整库存
- 批量创建或修改商品
- 正式订单状态变化

上述操作必须：

- 提交前完整校验
- 单事务提交
- 失败整体回滚
- 用户主动排除的行计入 `skippedCount`，不算系统失败

## 17. 数据库迁移要求

- 不依赖 Hibernate 自动建表；为所有新增字段和表提供明确 SQL 迁移。
- 迁移必须兼容已有数据。
- 新增非空字段必须提供默认值或分阶段迁移。
- 状态字符串改枚举时，先统计线上已有值并提供映射。
- 新增索引前评估现有数据量和锁表影响。
- 回滚脚本不得删除已经生成的业务结果或价格历史。

### 17.1 当前项目的迁移修订

当前 MySQL 配置使用 `spring.sql.init.mode=always` 和单一 `schema.sql`，只能保证新环境建表，不能可靠升级已有表。因此 P0-A 的第一项工作是引入 Flyway：

```text
springboot-demo/src/main/resources/db/migration/
  V1__baseline_existing_schema.sql
  V2__agent_progress_and_approval.sql
  V3__excel_async_task_columns.sql
  V4__excel_outputs.sql
  V5__product_price_history.sql
  V6__excel_comparisons.sql
```

- 已有环境采用一次性 baseline，先备份并核对实际表结构，再执行 V2 以后迁移。
- 新环境只运行 Flyway；生产关闭 `spring.sql.init.mode=always`。
- 测试环境可以继续装载测试数据，但表结构同样由 Flyway 创建，避免两套 DDL 漂移。
- 正向迁移只增加可空字段或带默认值字段，应用先做到“双读兼容”，再切换写入。
- 回滚以回滚应用版本为主；新增列和历史表不自动删除。需要清理时另行人工迁移。

如果本轮不引入 Flyway，则不得开始新增字段开发；仅修改 `schema.sql` 不视为完成数据库迁移交付。

## 18. 测试要求

### 18.1 Agent

- 正常任务阶段和进度更新。
- 等待审批时的结构化影响预览。
- 旧版本或错误 actionId 审批被拒绝。
- 审批过期。
- 处理中取消。
- 等待审批时取消。
- 重复取消幂等。
- 取消与工具执行并发竞争。
- 网络中断后通过 `GET /agent/runs/{runId}` 恢复。
- 多实例模拟下取消状态不会被后续结果覆盖。
- `PARTIAL`、`FAILED`、`CANCELLED` 的结构化结果。

### 18.2 Excel

- 上传快速返回，后台进入解析。
- `.xls`、`.xlsx`、`.csv` 正常解析。
- 超大文件和错误类型返回稳定错误码。
- 解析中取消。
- 解析失败后重试。
- 映射版本冲突。
- 审核版本冲突。
- 提交幂等。
- 提交前商品或库存变化时拒绝旧预览。
- 正式写入异常整体回滚。
- 用户排除行正确计入跳过数量。
- 报价文件生成、重复请求幂等、下载鉴权。
- 价格同步同时写价格历史。
- 版本比较大结果分页。

### 18.3 安全

- 操作人 A 不能查看、取消、确认或下载操作人 B 的任务。
- 任意 object key 不能直接下载。
- 非白名单下载地址不能由后端拉取。
- 错误响应不暴露数据库、路径、密钥或完整堆栈。

## 19. 联调验收标准

完成后应满足：

- Agent 执行中能返回明确阶段、数量和异常数。
- 前端可调用真实取消接口，取消后不会继续执行写工具。
- 用户离开页面后任务继续执行，返回页面能恢复进度。
- 价格、库存、商品状态等写入前可看到“会修改什么”和“不会修改什么”。
- 确认请求能够防止旧预览、重复确认和越权确认。
- Agent 结果返回成功、跳过、失败数量和业务跳转目标。
- Excel 上传后立即获得任务 ID，并能观察解析过程。
- 买家报价可以生成并安全下载真实文件。
- 厂家价格同步可以追溯供应商、原价、新价和来源任务。
- Excel 版本比较可以返回新增、删除、变化和未变化数量。
- 所有正式批量写入保持事务和幂等。
- 正常、空、失败、部分成功、网络恢复、用户取消均有稳定接口状态。

## 20. 推荐实施顺序

1. 引入 Flyway，建立现有库 baseline，并在空库和已有库副本各演练一次迁移。
2. 增加公共错误模型、Trace ID、状态/阶段枚举和只允许条件更新的状态转换组件。
3. 扩展 Agent Run DTO 与表字段，但先保持旧接口字段兼容；完成状态恢复测试。
4. 上线新版审批契约和预览摘要校验；旧布尔审批进入兼容期。
5. 拆分 Excel 创建协调器与后台 Worker，完成异步解析、租约、进度、取消和重试。
6. 将 Excel 查询、审核摘要和校验封装成 Agent 工具；最后接入 R2 正式提交工具。
7. 实现输出文件表、存储读取接口、报价生成、鉴权下载和清理任务。
8. 增加供应商业务上下文、价格历史及同事务写入。
9. 实现 Excel 比较任务和分页差异明细。
10. 增加读取 DTO 与分页，保留旧列表接口一个版本。
11. 完成故障注入、多实例竞争和全量回归后再开启前端功能开关。

每一步必须可独立部署；第 5 步之前不注册 Agent Excel 写工具，第 6 步通过后才允许前端展示 Excel 正式提交入口。

## 21. 后端交付物

- 接口设计确认稿或 OpenAPI 定义。
- 数据库正向迁移脚本和迁移说明。
- 新增/修改后的 DTO、状态枚举和错误码清单。
- Agent 与 Excel 状态流转说明。
- 后端实现代码。
- 单元测试和集成测试。
- 本地及 CloudBase 部署所需配置说明。
- 一份前后端联调示例数据。
- 尚未实现或受基础设施限制的能力清单。

## 22. 已确认默认值与仍需产品确认项

### 22.1 后端可以直接采用的默认值

1. 按可能多实例设计；首版使用数据库租约与取消标记，本机 Future 仅作优化。
2. 输出文件通过 `AttachmentStorage` 抽象；开发环境本地存储，生产 COS，数据库不保存临时 URL。
3. `SUPPLIER_PRICE` 必须选择供应商；价格生效日期为空时取门店时区当天并持久化。
4. Excel 版本比较默认使用条码，允许显式增加规格；重复键进入问题列表。
5. `PARTIAL` 只用于非写入任务。
6. 旧接口兼容一个联调版本；新增 R2 Excel 工具只支持新版审批。
7. 签名下载 URL 默认有效 10 分钟。

### 22.2 必须由产品或业务负责人确认

1. 报价模式是否允许无客户生成；后端在确认前不设置隐式价格默认值，调用方必须提交 `pricingMode`。
2. 源文件和输出文件保留期限。建议源文件 30 天、输出文件 7 天，但在确认前只做可配置项，不启用自动删除。
3. 客户专属价格当前是否有可靠数据源；如果没有，首版 `CUSTOMER_TYPE_PRICE` 只能映射现有零售价、批发价和老客户价规则。

这些产品问题不会阻塞 P0-A 的协议、状态和迁移基础，但会阻塞报价文件的生产发布。

## 23. 状态更新的统一实现约束

Agent 与 Excel 都新增状态转换组件，业务代码不能散落 `setStatus("...")`：

```text
transition(id, operatorId, expectedStatuses, targetStatus, stage, progressPatch)
```

转换使用数据库条件更新并返回受影响行数。失败后重新读取实体并区分：越权、版本冲突、已取消、已完成和租约丢失。日志记录 `fromStatus`、`toStatus`、`stageCode`、`operatorId/workerId`、`traceId`，但不记录附件正文或模型敏感输入。

每个枚举提供唯一的中文映射，不在 Controller、Service 和前端分别维护三套文案。

## 24. 联调响应示例

异步 Excel 创建成功：

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
```

```json
{
  "success": true,
  "message": "文件任务已创建，正在后台读取",
  "data": {
    "id": 108,
    "status": "UPLOADED",
    "progress": {
      "stageCode": "QUEUED",
      "stageText": "等待读取文件",
      "completedRows": null,
      "totalRows": null,
      "percent": null,
      "issueCount": 0,
      "updatedAt": "2026-10-05T15:20:18"
    },
    "canCancel": true,
    "canRetry": false,
    "version": 0
  },
  "error": null,
  "timestamp": "2026-10-05T15:20:18"
}
```

版本冲突：

```http
HTTP/1.1 409 Conflict
```

```json
{
  "success": false,
  "message": "任务内容已经变化，请刷新后重新确认",
  "data": null,
  "error": {
    "code": "EXCEL_TASK_VERSION_CONFLICT",
    "userMessage": "任务内容已经变化，请刷新后重新确认",
    "retryable": true,
    "retryAfterMs": 0,
    "traceId": "4d6b..."
  },
  "timestamp": "2026-10-05T15:20:18"
}
```
