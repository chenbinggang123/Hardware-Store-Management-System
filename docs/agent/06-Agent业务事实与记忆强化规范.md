# Agent 业务事实与记忆强化规范

> 状态：待开发评审
> 制定日期：2026-09-30
> 适用范围：`springboot-demo` 中的 Agent Harness、模型网关、工具协议、会话记忆与审计模块
> 优先级：P0（业务可信度与安全基础设施）

## 1. 文档目的

本规范用于解决当前 Agent 将“聊天历史”与“当前业务事实”混合使用的问题，并将现有依赖提示词的软约束，升级为 Harness 可以检查、拒绝和恢复的代码级约束。

改造后的核心原则是：

```text
聊天历史用于理解意图，不作为动态业务数据的真源。
业务数据库是商品、客户、库存、订单、价格和金额状态的唯一真源。
凡是可能变化的业务事实，必须在当前 Run 中取得有效工具证据。
没有当前工具证据，Agent 不得输出确定的业务数值、状态或执行写操作。
```

本文档是开发规范，不表示其中能力已经实现。

## 2. 当前实现基线

### 2.1 当前会话记忆

当前系统以 `conversationId` 隔离会话。新 Run 会读取同一会话最近 10 个 Run，并将每个 Run 的：

- `inputText` 转成 `user` 消息；
- `outputText` 转成 `assistant` 消息。

历史工具参数和工具结果不会进入后续 Run；它们只保存在工具调用审计表中。附件解析文本只在附件首次发送的 Run 中注入模型，后续 Run 不会自动重新读取。

### 2.2 当前 Run 内工作记忆

当前 Run 已完成的工具调用保存在 `AgentRunContext.toolResults` 中。每次再次调用模型时，模型会看到本 Run 已执行工具的参数和结果。

遇到需要确认的工具时，Harness 会将工具结果、待确认工具和参数保存到 `agent_run.context_json` 及 `pending_*` 字段，确认后恢复执行。

### 2.3 当前业务事实获取

模型根据系统提示词和工具描述，自主决定是否调用：

- `search_products`
- `search_customers`
- `check_inventory`
- `get_sales_draft`
- `create_sales_draft`
- `update_sales_draft`
- `commit_sales_draft`

写操作会经过 Java 业务服务重新校验。查询类回答目前没有统一的工具证据校验。

## 3. 当前问题

### P01：动态业务值可能直接从历史回答复用

历史消息中可能存在旧库存、旧价格、旧欠款和旧订单状态。当前 Harness 无法判断模型最终回答中的数值来自历史、工具还是模型猜测。

示例：

```text
昨天历史：电钻库存 24 把
今天数据库：电钻库存 19 把
用户：现在还有多少？
```

如果模型不调用 `check_inventory` 而直接回答 24，Harness 仍会接受该最终回答。

### P02：业务事实查询依赖模型自觉

当前系统提示词要求“只能根据工具返回的数据回答”，但这是软约束。模型未调用工具时，代码没有统一机制要求重试或拒绝回答。

### P03：没有事实类型和新鲜度模型

工具结果没有统一描述：

- 数据来源；
- 查询时间；
- 实体版本；
- 有效期；
- 事实类型；
- 是否允许跨 Run 复用。

因此 Harness 无法判断某个证据是否足以支持当前回答或写操作。

### P04：历史工具证据与会话文本没有明确分层

过去 Run 的最终回答会作为普通 `assistant` 消息重新发送，模型可能把其中的业务数值误认为当前事实。

### P05：附件内容不可在后续轮次可靠引用

附件的 `extractedText` 会持久化，但没有 `read_attachment` 工具，也不会在后续 Run 自动注入。用户后续询问附件细节时，模型只能依赖上一轮回答中的摘要。

### P06：等待确认恢复时丢失增强后的模型输入

附件首次发送时，模型输入是“用户文字 + 附件解析文本”；但 `agent_run.input_text` 只保存用户可见文字。确认恢复后，原始附件文本不会重新进入模型上下文。

### P07：没有结构化最终答案协议

模型最终只返回字符串。Harness 无法识别：

- 回答包含哪些业务结论；
- 每个结论依赖哪个工具结果；
- 是否使用了未经验证的历史事实；
- 回答是否应标记为不确定。

### P08：缺少按 Token 的上下文预算和摘要机制

当前固定保留最近 10 个 Run，不计算模型上下文长度。可能出现历史过短导致信息不足，或历史过长导致请求超限。

### P09：工具审计数据未形成可复用证据

`agent_tool_call` 保存参数和结果，但没有证据 ID、事实类型、实体标识、版本和过期时间，无法安全地重新用于推理。

### P10：缺少强制回归测试

当前尚无针对以下行为的统一测试：

- 历史中有旧值时必须重新查询；
- 没有工具证据时不得回答当前值；
- 工具证据过期时必须重新查询；
- 确认恢复后证据链保持完整；
- 附件在后续轮次按权限重新读取。

## 4. 改造目标

### 4.1 必须达到

1. 明确区分会话上下文、业务事实证据和运行检查点。
2. 对动态业务问题强制要求当前 Run 的工具证据。
3. 工具结果携带统一的来源、时间、实体和版本信息。
4. Harness 能在模型给出最终回答前检查证据是否充分。
5. 写操作继续由 Java 业务服务重新校验，不能信任模型提供的金额、库存或状态。
6. 历史消息中的业务数值默认视为不可信旧信息。
7. 附件内容可以在后续 Run 中通过受控工具按需读取。
8. 保留现有单 Agent、白名单工具、人工确认和业务页面降级方案。

### 4.2 本期不做

- 多 Agent 协作。
- 通用向量知识库。
- 自动学习并写入用户偏好。
- 任意 SQL、Shell 或网络访问。
- 让模型直接访问 Repository。
- 使用模型输出替代 Java 金额、库存、权限、事务和幂等校验。

## 5. 目标概念模型

### 5.1 三类上下文必须分开

#### A. Conversation Context

用于理解：

- 用户意图；
- 代词指代；
- 已讨论对象；
- 用户刚才要求的修改方向。

它不能作为动态业务数值的权威来源。

#### B. Business Evidence

由当前 Run 的白名单工具产生，用于证明：

- 当前库存；
- 当前商品状态；
- 当前客户欠款；
- 当前草稿内容和版本；
- 当前订单状态；
- 当前价格或历史成交价查询结果。

#### C. Workflow Checkpoint

用于暂停和恢复：

- 当前步骤；
- 已执行工具；
- 待确认工具；
- 待确认参数；
- 确认预览；
- 当前证据集合；
- 附件引用。

### 5.2 事实分类

至少定义以下类型：

| 类型 | 示例 | 是否允许从聊天历史直接回答 |
| --- | --- | --- |
| `CONVERSATION_REFERENCE` | “他”指老王、“那个电钻”指商品 12 | 可以用于解析引用 |
| `STABLE_IDENTIFIER` | 客户 ID、商品 ID、草稿 ID | 可以引用，但工具执行时必须校验 |
| `MUTABLE_FACT` | 库存、欠款、价格、状态、草稿版本 | 不允许，必须取得当前证据 |
| `DERIVED_FACT` | 订单总额、欠款增加额、毛利 | 必须由可信工具或 Java 服务计算 |
| `USER_PREFERENCE` | 常用仓库、默认付款方式 | 本期不自动记忆 |

## 6. 工具证据协议

### 6.1 统一响应包

所有用于回答业务事实的工具应返回统一的 `AgentToolEvidenceEnvelope<T>`：

```json
{
  "evidenceId": "ev_01J...",
  "toolCallId": "call_123",
  "toolName": "check_inventory",
  "factType": "INVENTORY_SNAPSHOT",
  "source": "mysql.inventory",
  "observedAt": "2026-09-30T10:24:31+08:00",
  "validUntil": "2026-09-30T10:24:41+08:00",
  "entityRefs": [
    { "type": "PRODUCT", "id": "12", "version": "2026-09-30T10:24:18" }
  ],
  "reusableAcrossRuns": false,
  "data": {
    "productId": 12,
    "productName": "东成手电钻",
    "quantity": 19,
    "locationId": "A-03"
  }
}
```

### 6.2 最低字段要求

- `evidenceId`：服务端生成，模型不得提供。
- `toolCallId`：关联模型本次工具调用。
- `toolName`：证据来源工具。
- `factType`：用于策略匹配。
- `source`：真实数据源名称。
- `observedAt`：读取数据的时间。
- `validUntil`：证据有效期；不适用时可为空。
- `entityRefs`：业务实体 ID 和可用版本。
- `reusableAcrossRuns`：默认 `false`。
- `data`：原工具业务结果。

### 6.3 建议的新鲜度

| 事实 | 建议有效期 | 写操作时处理 |
| --- | ---: | --- |
| 库存 | 10 秒 | 必须在事务内重新检查 |
| 商品上下架状态 | 60 秒 | 保存/提交时重新检查 |
| 客户欠款 | 10 秒 | 提交订单/收款时重新计算 |
| 草稿内容和版本 | 当前 Run | 更新/提交时比较版本 |
| 商品基础资料 | 5 分钟 | 引用时校验实体仍存在 |
| 历史成交价 | 当前 Run | 成交时按业务规则重新解析 |

有效期是回答层的参考，不能替代写操作事务内校验。

## 7. 业务事实策略

### 7.1 建议新增 `AgentFactPolicy`

职责：

1. 根据用户意图识别需要的事实类型。
2. 声明必须具备的工具证据。
3. 检查证据是否属于当前 Run、当前操作人和当前实体。
4. 检查证据是否过期。
5. 在证据不足时返回缺失项，而不是允许最终回答。

建议接口：

```java
public interface AgentFactPolicy {
    FactRequirementSet requirements(AgentRunContext context, AgentModelResponse response);

    FactValidationResult validate(
            AgentRunContext context,
            AgentFinalAnswer answer,
            List<AgentEvidence> evidence);
}
```

### 7.2 初期意图与工具映射

| 意图 | 必需证据 | 允许工具 |
| --- | --- | --- |
| 查询当前库存 | `INVENTORY_SNAPSHOT` | `check_inventory` |
| 判断库存是否充足 | `INVENTORY_SNAPSHOT` | `check_inventory` 或草稿预览服务 |
| 查询商品当前状态 | `PRODUCT_SNAPSHOT` | `search_products` / 新增详情工具 |
| 查询客户当前欠款 | `CUSTOMER_ACCOUNT_SNAPSHOT` | 新增 `get_customer_account` |
| 查询销售草稿 | `SALES_DRAFT_SNAPSHOT` | `get_sales_draft` |
| 修改销售草稿 | `SALES_DRAFT_SNAPSHOT` + 版本 | `get_sales_draft`、`update_sales_draft` |
| 提交正式销售单 | 最新草稿、库存、客户账户证据 | `commit_sales_draft` 内部重新检查 |
| 查询历史成交价 | `CUSTOMER_PRODUCT_PRICE_HISTORY` | 新增 `get_customer_product_price` |

### 7.3 历史消息处理规则

传给模型的历史消息前，应增加固定的历史边界说明：

```text
以下历史仅用于理解对话上下文。历史中的库存、价格、欠款、金额、
订单状态、草稿内容和其他可能变化的数据均可能过期，不得作为当前事实。
当用户询问当前值或基于当前值执行操作时，必须调用相应工具。
```

不要求删除历史中的数值，因为它们对解释前后变化有价值；但模型和 Harness 都不能将其视为当前证据。

## 8. 模型输出协议

### 8.1 将最终字符串升级为结构化结果

模型最终输出建议改为：

```json
{
  "type": "final_answer",
  "answer": "截至 10:24，东成手电钻当前库存 19 把。",
  "claims": [
    {
      "claimType": "CURRENT_INVENTORY",
      "entityType": "PRODUCT",
      "entityId": "12",
      "value": 19,
      "unit": "把",
      "evidenceIds": ["ev_01J..."]
    }
  ]
}
```

### 8.2 Harness 验证规则

1. 没有 `claims` 的一般说明可以直接返回。
2. 包含动态业务数值或状态的 claim 必须引用至少一个证据。
3. 证据必须属于当前 Run。
4. `claimType` 必须与证据 `factType` 匹配。
5. 实体 ID 必须匹配。
6. claim 中的值必须可以从证据中验证，或由服务端计算器复算。
7. 证据过期时不得返回“当前”“实时”“现在”等确定表述。
8. 验证失败时 Harness 应让模型修正一次；仍失败则返回安全降级提示。

### 8.3 安全降级文案

证据不足时不得猜测，应返回：

```text
我还没有取得当前业务数据，暂时不能确认这个数值。请稍后重试。
```

如果可以明确缺少的工具能力：

```text
当前还没有查询客户实时欠款的业务工具，因此不能可靠回答该问题。
```

## 9. Harness 改造要求

### 9.1 `AgentRunContext`

新增：

```text
conversationContext
currentInput
attachmentRefs
toolResults
evidence
requiredFacts
validationFailures
```

不要继续使用无类型的 `List<Map<String, Object>>` 作为长期协议。应定义明确 DTO，并保留 JSON 兼容迁移逻辑。

### 9.2 Agent Loop

目标流程：

```text
载入会话上下文
→ 标记历史为非权威上下文
→ 调用模型
→ 模型请求工具：策略检查后执行
→ 将工具结果包装成证据
→ 继续调用模型
→ 模型返回结构化最终回答
→ FactPolicy 验证 claims 与 evidence
→ 通过：完成 Run
→ 不通过：返回缺失证据让模型修正
→ 达到修正上限：安全降级并结束
```

### 9.3 循环限制

保留当前限制：

- 最多 6 次模型调用；
- 最多 10 次工具调用；
- 相同工具和相同参数禁止在同一 Run 无条件重复调用。

但应允许以下受控重查：

- 原证据已过期；
- 前一次调用明确失败；
- 写操作完成后需要重新读取变化后的状态；
- 工具定义声明 `repeatable=true` 且策略给出原因。

建议把重复检测从简单指纹升级为：

```text
toolName + normalizedArguments + evidenceGeneration + repeatReason
```

### 9.4 暂停与恢复

Checkpoint 必须保存：

- 已执行工具结果；
- 标准化证据；
- 附件 ID；
- 当前意图和事实需求；
- 待确认动作；
- 模型步骤数和工具调用数。

确认恢复时：

1. 重新校验身份和 Run 状态。
2. 重新加载附件引用，不依赖恢复后的原始提示词。
3. 检查旧证据是否过期。
4. 写操作必须重新读取业务数据并在事务内校验。
5. 写成功但模型总结失败时，仍保持业务成功，返回确定性服务端结果。

### 9.5 不允许的实现

- 不允许通过隐藏历史数值来代替证据验证。
- 不允许仅修改系统提示词后宣称问题已解决。
- 不允许让模型自行声明证据已经验证。
- 不允许复用其他操作人或其他 Run 的短期证据。
- 不允许将聊天历史直接写回业务表。
- 不允许为了“记忆”而给模型直接数据库权限。

## 10. 附件记忆改造

### 10.1 新增 `read_attachment`

建议新增 R0 工具：

```text
read_attachment
```

输入：

```json
{
  "attachmentId": 123,
  "query": "读取第 73 行，或查找商品名称包含电钻的行"
}
```

要求：

- 只能读取当前操作人附件；
- 默认只能读取当前会话附件；
- 返回内容长度受限；
- 支持行号、关键词或分页读取；
- 工具返回内容继续标记为不可信附件数据；
- 附件内容不得作为系统指令执行；
- 已删除、失败或未解析附件不得读取。

### 10.2 不自动回放完整附件

后续 Run 不应自动把全部附件文本放进上下文，否则会导致：

- Token 不受控；
- 提示词注入风险扩大；
- 不相关内容污染推理。

正确方式是保留附件引用，并通过工具按需读取。

## 11. 数据库建议

### 11.1 新增证据表（推荐）

```sql
CREATE TABLE agent_evidence (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    evidence_key VARCHAR(64) NOT NULL,
    run_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    tool_call_id VARCHAR(100),
    tool_name VARCHAR(64) NOT NULL,
    fact_type VARCHAR(64) NOT NULL,
    source_name VARCHAR(128) NOT NULL,
    entity_refs_json LONGTEXT,
    data_json LONGTEXT NOT NULL,
    observed_at DATETIME NOT NULL,
    valid_until DATETIME,
    reusable_across_runs TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    UNIQUE KEY uk_agent_evidence_key (evidence_key),
    KEY idx_agent_evidence_run (run_id),
    KEY idx_agent_evidence_operator (operator_id)
);
```

### 11.2 `agent_run`

建议增加：

```text
checkpoint_version
required_facts_json
validation_failures_json
attachment_refs_json
```

如暂不增加字段，可以先将新版结构放入 `context_json`，但必须包含 `schemaVersion`。

示例：

```json
{
  "schemaVersion": 2,
  "toolResults": [],
  "evidence": [],
  "requiredFacts": [],
  "attachmentIds": []
}
```

## 12. 安全与隐私要求

1. 证据必须绑定 `operatorId` 和 `runId`。
2. 附件读取必须校验操作人和会话。
3. 工具审计和证据保存需要统一敏感字段脱敏策略。
4. 手机号、地址、客户联系方式不得无条件进入模型上下文。
5. 模型不能通过参数指定 `operatorId`。
6. 模型不能提供 `evidenceId` 冒充服务端证据。
7. 证据 ID 必须由服务端生成并从当前 Run 的证据集合中解析。
8. 写操作确认前后的业务快照都应可审计。
9. 附件文本始终是低信任数据，不能覆盖系统提示、工具策略和确认策略。

## 13. 可观测性

至少记录以下指标：

- 每个意图的工具调用率；
- 动态事实问题无工具回答拦截次数；
- 证据验证失败次数和原因；
- 证据过期后重新查询次数；
- 模型修正成功率；
- 安全降级次数；
- 每个 Run 的模型调用次数、工具调用次数和 Token 使用量；
- 附件按需读取次数和截断次数；
- 写操作确认前后重新校验失败次数。

日志中要区分：

```text
MODEL_REQUESTED_TOOL
TOOL_EXECUTED
EVIDENCE_CREATED
FINAL_ANSWER_REJECTED
FINAL_ANSWER_ACCEPTED
SAFE_FALLBACK_RETURNED
```

## 14. 开发阶段划分

### 阶段一：P0 最小可信闭环

1. 增加历史非权威说明。
2. 定义 `AgentEvidence`、`AgentClaim`、`AgentFinalAnswer` DTO。
3. 将 `check_inventory`、`get_sales_draft` 改为返回标准证据。
4. 增加最终回答证据校验。
5. 没有库存证据时禁止回答当前库存。
6. 没有草稿证据时禁止回答当前草稿状态。
7. 为证据不足增加一次模型修正机会。
8. 增加对应单元测试。

### 阶段二：P0 写操作证据链

1. Checkpoint schema 升级。
2. 确认恢复时恢复证据和附件引用。
3. 草稿创建、修改、提交统一生成服务端执行证据。
4. 将确认前预览和确认后结果关联到同一 Run。
5. 增加库存变化、草稿版本变化、重复确认并发测试。

### 阶段三：P1 补齐业务查询工具

建议新增：

- `get_product_detail`
- `get_customer_detail`
- `get_customer_account`
- `get_customer_product_price`
- `get_sales_order`
- `read_attachment`

同时建立意图与必需证据映射。

### 阶段四：P1 上下文治理

1. 从固定 10 Run 升级为 Token 预算裁剪。
2. 为旧历史生成非权威摘要。
3. 分离会话引用摘要与业务数值。
4. 增加会话删除、附件清理和数据保留策略。

### 阶段五：P2 评测和运营

1. 建立真实表达评测集。
2. 统计错误使用历史值的比例。
3. 灰度开启证据门禁。
4. 对安全降级案例补工具或规则。

## 15. 必测用例

### 15.1 历史旧值

```text
历史回答：库存 24
数据库当前值：19
用户：现在还有多少？
```

验收：必须调用库存工具，最终回答 19，并引用当前证据。

### 15.2 模型直接回答旧值

模拟模型未调用工具，直接输出“库存 24”。

验收：Harness 拒绝该回答并要求补充证据；修正失败则安全降级。

### 15.3 指代解析与实时查询

```text
上一轮已确认“那个电钻”是 productId=12
本轮：它现在还有多少？
```

验收：允许复用商品 ID，但必须重新调用库存工具。

### 15.4 证据过期

库存证据生成后超过有效期，模型再次声称“当前库存”。

验收：必须重新查询，不得复用过期证据。

### 15.5 写操作前库存变化

确认卡显示库存充足，但用户确认前库存被其他订单修改。

验收：事务内重新检查并拒绝提交，不得使用确认卡旧库存。

### 15.6 草稿版本变化

确认卡基于版本 2，确认前草稿变成版本 3。

验收：拒绝更新或提交，并要求重新预览。

### 15.7 附件后续读取

第一轮上传 Excel，第二轮询问第 73 行。

验收：通过 `read_attachment` 按需读取，不能依赖第一轮摘要猜测。

### 15.8 跨会话隔离

会话 A 上传附件或取得证据，会话 B 尝试引用。

验收：默认拒绝；业务数据库中的本人业务实体仍可通过正常工具重新查询。

### 15.9 跨用户隔离

操作人 2 尝试读取操作人 1 的附件、Run、证据或草稿。

验收：全部拒绝。

### 15.10 模型总结失败

正式写操作成功后，模型生成最终说明失败。

验收：Run 保持业务成功，返回服务端确定性结果，不重复执行写操作。

## 16. 验收标准

完成 P0 后必须满足：

- [ ] 当前库存问题无当前 Run 库存证据时，确定性回答通过率为 0。
- [ ] 当前草稿状态问题无草稿证据时，确定性回答通过率为 0。
- [ ] 历史中存在旧值时，系统仍会重新查询当前值。
- [ ] 写操作不依赖模型声明的库存、金额、版本或权限。
- [ ] 确认恢复后工具结果和证据链不丢失。
- [ ] 重复确认不会重复写入。
- [ ] 证据不能跨操作人使用。
- [ ] 安全降级不会编造业务数据。
- [ ] 新增测试全部通过，原有测试无回归。
- [ ] 文档、工具定义和实际风险策略一致。

建议量化指标：

| 指标 | 目标 |
| --- | ---: |
| 动态事实无证据回答率 | 0% |
| 高风险写操作绕过确认率 | 0% |
| 重复确认重复写入率 | 0% |
| 旧库存误答率 | 0% |
| 证据验证失败后的安全降级率 | 100% |
| 正常查询额外模型调用 | 不超过 1 次修正 |

## 17. 建议代码结构

```text
agent/
├── evidence/
│   ├── AgentEvidence.java
│   ├── AgentEvidenceEnvelope.java
│   ├── AgentEvidenceFactory.java
│   ├── AgentEvidenceRepository.java
│   └── AgentFactType.java
├── claim/
│   ├── AgentClaim.java
│   ├── AgentFinalAnswer.java
│   └── AgentClaimValidator.java
├── policy/
│   ├── AgentPolicyEngine.java
│   ├── AgentFactPolicy.java
│   ├── FactRequirement.java
│   └── FactValidationResult.java
├── harness/
│   ├── AgentHarness.java
│   ├── AgentRunContext.java
│   └── AgentCheckpoint.java
└── tool/
    ├── AgentTool.java
    ├── AgentToolResult.java
    └── builtin/
```

无需一次性完成目录重构。优先落地 DTO、证据验证和测试，再逐步移动代码。

## 18. 开发交付要求

开发提交时必须同时提供：

1. 代码改动清单。
2. 数据库迁移脚本。
3. 新旧 Checkpoint 兼容说明。
4. 工具协议示例。
5. 单元测试和集成测试报告。
6. 至少 20 条动态事实评测案例。
7. 已知未覆盖事实类型清单。
8. 灰度、回滚和数据清理方案。

如果只修改系统提示词、没有加入 Harness 证据校验，不视为完成本规范。
