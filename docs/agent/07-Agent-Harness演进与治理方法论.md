# Agent Harness 演进与治理方法论

> 文档类型：团队知识库 / 工程治理规范
> 状态：建议采用
> 制定日期：2026-09-30
> 适用对象：产品负责人、架构师、Agent 开发、业务后端、测试与运维人员

## 1. 为什么需要这份方法论

Agent Harness 不只是“调用一次模型，再执行几个工具”的代码。随着能力增长，它会逐渐包含：

- 系统提示词；
- 上下文选择；
- 对话记忆；
- 工具协议；
- 权限和风险策略；
- 人工确认；
- 暂停与恢复；
- 模型供应商适配；
- 业务证据；
- 错误恢复；
- 运行审计；
- 评测和灰度发布。

如果每次优化只修改代码或提示词，却不记录背景、决策、版本和效果，Harness 会迅速出现以下问题：

1. 不知道某段限制为什么存在，不敢删除。
2. 同一个问题被不同开发重复修复。
3. 新模型上线后，旧的补丁仍然保留并互相冲突。
4. 一次修改同时影响 Prompt、工具、状态和权限，无法定位回归来源。
5. 线上问题无法还原当时使用的模型、工具和策略。
6. 团队只能凭主观感受判断 Agent 是否“变聪明了”。
7. 安全边界逐渐隐藏在零散代码和提示词中。

因此，Agent 开发必须同时维护两种系统：

```text
运行系统：真正处理用户任务的 Harness。
认知系统：记录为什么这样设计、改了什么、效果如何、如何回滚。
```

缺少第二套系统，第一套系统越复杂，维护风险越高。

## 2. 核心理念

### 2.1 优先使用最简单、可解释的结构

不要因为行业中存在多 Agent、规划器、反思器、向量记忆和通用沙箱，就一次性全部引入。

复杂组件只有在满足以下条件时才应加入：

1. 有明确、可复现的问题。
2. 当前简单方案无法解决。
3. 可以定义验证指标。
4. 可以独立启用和关闭。
5. 可以说明新增成本和失败方式。

每个 Harness 组件都隐含一个假设，例如：

```text
增加 Planner，假设模型无法自行规划。
增加 Evaluator，假设模型无法自行发现错误。
增加长期记忆，假设跨会话信息能提高任务成功率。
增加上下文摘要，假设原始历史太长且摘要损失可接受。
```

模型能力升级后，这些假设可能不再成立。组件不能因为“已经存在”就永久保留。

### 2.2 将 Agent 视为非确定性系统

传统函数通常满足：

```text
相同输入 + 相同代码 → 基本相同输出
```

Agent 系统不完全满足这个条件。它还受以下因素影响：

- 模型版本；
- 模型供应商；
- 推理参数；
- 系统提示词；
- 工具描述和顺序；
- 上下文内容；
- 外部业务数据；
- 模型服务端行为；
- 中间工具失败和延迟。

因此，Agent 的“可复现”不是要求生成逐字相同的回答，而是能够还原：

```text
当时使用什么模型、提示词、工具集、策略、上下文和业务状态，
为什么走到这条执行路径，以及安全规则是否被正确执行。
```

### 2.3 先建立 Eval，再修改行为

遇到线上问题时，不应直接修改 Prompt 或 Harness。

正确顺序：

```text
收集失败轨迹
→ 最小化复现条件
→ 新增 Eval Case
→ 确认旧版本稳定失败
→ 实施最小改动
→ 确认新版本通过
→ 跑完整回归集
→ 记录指标变化
```

这样可以确保团队不是“凭感觉优化”。

### 2.4 一次实验尽量只改变一个主要变量

不要在同一次优化中同时：

- 更换模型；
- 重写系统提示词；
- 修改工具名称；
- 修改工具返回结构；
- 改变上下文选择规则；
- 改变确认策略。

否则结果变好或变差时，无法判断原因。

建议每个实验只改变一个主要变量，其他条件保持固定。

### 2.5 业务安全由确定性代码负责

模型适合：

- 理解自然语言；
- 提取用户意图；
- 选择工具；
- 解释结果；
- 在候选方案中进行推理。

确定性代码负责：

- 身份和权限；
- 金额计算；
- 库存校验；
- 数据版本；
- 事务；
- 幂等；
- 风险策略；
- 人工确认；
- 审计；
- 最终业务写入。

不能通过“提示模型小心一点”替代业务安全机制。

### 2.6 上下文工程比单纯提示词工程更重要

系统提示词只是模型输入的一部分。真正影响 Agent 行为的是完整上下文：

```text
系统规则
+ 工具定义
+ 历史消息
+ 当前输入
+ 附件内容
+ 当前 Run 工具结果
+ 业务证据
+ 运行状态
```

优化 Agent 时，应明确回答：

- 哪些内容进入模型？
- 哪些内容不进入模型？
- 哪些内容是权威事实？
- 哪些内容只是历史参考？
- 哪些内容需要压缩？
- 哪些内容必须重新查询？

### 2.7 工具质量通常比增加更多 Agent 更重要

工具是确定性业务系统和非确定性模型之间的协议。高质量工具应该：

- 名称清楚；
- 职责单一；
- 输入 Schema 严格；
- 返回模型真正需要的上下文；
- 返回结果长度可控；
- 错误信息可行动；
- 风险等级明确；
- 支持审计和版本化；
- 不暴露不必要的敏感字段。

在当前项目中，优先补齐可靠的业务查询工具，通常比引入多 Agent 更有价值。

## 3. Harness 演进闭环

建议所有重要优化遵循以下闭环：

```text
用户反馈 / 线上异常 / 新业务需求
                ↓
问题分类与失败轨迹
                ↓
建立可复现 Eval
                ↓
形成设计决策 ADR
                ↓
实施最小代码变更
                ↓
确定性测试 + Agent Eval + 安全测试
                ↓
版本化并进入灰度环境
                ↓
监控成功率、延迟、成本和安全指标
                ↓
扩大发布或回滚
                ↓
更新 Changelog、评测结果和遗留问题
```

### 3.1 问题分类

每个问题首先归类：

| 分类 | 典型问题 |
| --- | --- |
| Model | 模型能力不足、供应商行为变化 |
| Prompt | 系统规则不清楚、示例误导 |
| Context | 历史污染、附件丢失、Token 超限 |
| Tool | 工具难选择、参数不清、返回内容不足 |
| Policy | 风险等级错误、缺少确认、越权 |
| State | Checkpoint 损坏、恢复重复执行 |
| Evidence | 使用旧业务值、结论无工具证据 |
| Business | 业务服务计算或事务错误 |
| UI | 确认卡表达不清、状态无法恢复 |
| Infrastructure | 超时、限流、模型网关、数据库故障 |

不先分类，就容易用 Prompt 修复本应由代码解决的问题。

### 3.2 最小改动原则

每个修复应明确：

```text
问题是什么？
根因在哪一层？
最小改动是什么？
哪些不变量必须保持？
如何证明改动有效？
如何证明没有破坏其他能力？
如何快速关闭或回滚？
```

### 3.3 灰度发布

高影响 Harness 改动建议按以下顺序发布：

```text
本地评测
→ 自动化测试环境
→ 内部账号
→ 测试门店
→ 少量真实用户
→ 全量
```

建议为高影响能力提供功能开关：

```text
AGENT_FACT_VALIDATION_ENABLED
AGENT_STRUCTURED_FINAL_ANSWER_ENABLED
AGENT_ATTACHMENT_READ_TOOL_ENABLED
AGENT_CONTEXT_SUMMARY_ENABLED
AGENT_NEW_POLICY_ENGINE_ENABLED
```

功能开关应包含删除日期，不能无限期保留。

## 4. 四套必须长期维护的记录

### 4.1 Changelog：记录改了什么

建议文件：

```text
docs/agent/CHANGELOG.md
```

每次发布至少记录：

- Added；
- Changed；
- Fixed；
- Security；
- Database；
- Evaluation；
- Risks；
- Rollback。

模板：

```markdown
## [版本] - 日期

### Added
- 新增了什么能力。

### Changed
- 哪些行为或协议发生变化。

### Fixed
- 修复了哪些可复现问题。

### Security
- 权限、确认、隔离或敏感数据发生什么变化。

### Database
- Schema、迁移和兼容情况。

### Evaluation
- 基线和新版本指标对比。

### Risks
- 已知副作用和未覆盖情况。

### Rollback
- 功能开关和回滚步骤。
```

### 4.2 ADR：记录为什么这样做

建议目录：

```text
docs/agent/adr/
```

适合写 ADR 的变化：

- 单 Agent 或多 Agent；
- R1/R2 是否确认；
- 记忆隔离方式；
- Checkpoint 结构；
- 业务证据协议；
- 是否引入框架；
- 是否允许代码执行；
- 附件如何读取；
- 数据保留策略。

模板：

```markdown
# ADR-XXXX：决策名称

状态：Proposed / Accepted / Deprecated / Superseded
日期：
负责人：

## 背景
问题、约束和现有失败。

## 决策
决定采用什么方案。

## 备选方案
考虑过哪些方案。

## 选择理由
为什么选择当前方案。

## 影响
收益、成本和风险。

## 不变量
不能被破坏的安全和业务边界。

## 回滚方式
怎样恢复到旧行为。

## 关联
Issue、PR、Eval、事故和文档。
```

旧 ADR 不应覆盖。如果决策变化，应新增 ADR 并声明替代关系。

### 4.3 Eval Registry：记录是否真的变好

建议目录：

```text
springboot-demo/src/test/resources/agent-evals/
├── inventory/
├── customer/
├── product/
├── draft/
├── approval/
├── attachment/
├── memory/
└── security/
```

每个线上失败都应转成永久评测案例。

建议格式：

```yaml
id: inventory-stale-001
category: mutable-fact
description: 历史库存为24，数据库当前库存为19

conversation:
  - role: user
    content: 东成电钻还有多少？
  - role: assistant
    content: 当前库存24把

currentInput: 现在还有多少？

fixtures:
  productId: 12
  currentInventory: 19

expected:
  requiredTools:
    - check_inventory
  mustHaveEvidence: true
  claims:
    - type: CURRENT_INVENTORY
      entityId: "12"
      value: 19
```

每次 Harness 发布应保存评测报告：

```text
eval-results/
└── 2026-10-15-harness-0.3.0.json
```

报告至少包括：

- 评测集版本；
- 模型和参数；
- Harness/Prompt/Toolset/Policy 版本；
- 各类别通过率；
- 工具误调用率；
- 无证据回答率；
- 平均模型调用数；
- 平均工具调用数；
- Token 和费用；
- P50/P95 延迟；
- 与基线的差异。

### 4.4 Run Trace：记录一次运行发生了什么

每个 Run 至少保存：

```text
runId
conversationId
operatorId
applicationVersion
harnessVersion
promptVersion
promptHash
toolsetVersion
toolSchemaHash
policyVersion
contextBuilderVersion
checkpointSchemaVersion
modelProvider
modelName
modelParameters
startedAt
completedAt
status
```

事件轨迹建议采用追加式记录：

```text
RUN_CREATED
CONTEXT_BUILT
MODEL_REQUESTED
MODEL_RESPONDED
TOOL_REQUESTED
TOOL_ALLOWED
TOOL_EXECUTED
EVIDENCE_CREATED
APPROVAL_REQUESTED
APPROVAL_RESOLVED
FINAL_ANSWER_REJECTED
FINAL_ANSWER_ACCEPTED
RUN_COMPLETED
RUN_FAILED
```

这套事件可以继续保存在关系数据库，不需要立即引入复杂事件流平台。

## 5. 版本体系

不要只维护一个应用版本。建议至少区分：

```text
applicationVersion
harnessVersion
promptVersion
toolsetVersion
policyVersion
contextBuilderVersion
checkpointSchemaVersion
evalSetVersion
```

示例：

```json
{
  "applicationVersion": "1.4.0",
  "harnessVersion": "0.3.0",
  "promptVersion": "sales-agent-v7",
  "toolsetVersion": "tools-v5",
  "policyVersion": "policy-v3",
  "contextBuilderVersion": "context-v2",
  "checkpointSchemaVersion": 2,
  "evalSetVersion": "2026-10-15"
}
```

### 5.1 Prompt 版本化

建议：

```text
springboot-demo/src/main/resources/agent/prompts/
├── system-v1.txt
├── system-v2.txt
├── system-v3.txt
└── manifest.yaml
```

不要直接覆盖唯一一份 Prompt 后无法追溯。

### 5.2 工具版本化

工具定义建议增加：

```text
name
version
risk
inputSchemaVersion
outputSchemaVersion
```

修改返回字段、含义或副作用时必须升级版本。

### 5.3 Checkpoint 版本化

所有持久化 Run 上下文必须包含：

```json
{
  "schemaVersion": 2,
  "toolResults": [],
  "evidence": [],
  "requiredFacts": [],
  "attachmentIds": []
}
```

新版代码必须：

- 能读取仍处于等待状态的旧 Checkpoint；或
- 明确让旧任务安全过期；
- 不能静默误解旧字段含义。

## 6. Harness 模块边界

建议逐步形成以下结构：

```text
AgentOrchestrator
├── ContextBuilder
├── ModelGateway
├── ToolRegistry
├── ToolExecutor
├── PolicyEngine
├── FactPolicy
├── ClaimValidator
├── CheckpointStore
├── TraceRecorder
└── RunLifecycleService
```

| 模块 | 职责 |
| --- | --- |
| AgentOrchestrator | 控制 Agent Loop，不承载具体业务逻辑 |
| ContextBuilder | 选择、裁剪、标记模型上下文 |
| ModelGateway | 适配模型协议、超时和响应解析 |
| ToolRegistry | 注册和版本化白名单工具 |
| ToolExecutor | 参数校验、执行、错误标准化和超时 |
| PolicyEngine | 判断允许、确认或拒绝工具 |
| FactPolicy | 判断回答需要哪些业务证据 |
| ClaimValidator | 验证最终回答和证据是否一致 |
| CheckpointStore | 保存、恢复和迁移执行状态 |
| TraceRecorder | 记录追加式运行轨迹 |
| RunLifecycleService | 管理 Run 状态机和并发抢占 |

原则：

- 每个模块可独立单元测试。
- 工具不能绕过 PolicyEngine。
- 状态变化必须经过生命周期服务。
- ContextBuilder 不直接执行业务查询。
- ModelGateway 不做业务授权。
- AgentOrchestrator 不直接访问 Repository。

## 7. PR 与代码评审规范

Agent 相关 PR 应使用以下模板：

```markdown
## 问题
这次修改解决哪个真实失败案例或业务目标？

## 根因分类
- [ ] Model
- [ ] Prompt
- [ ] Context
- [ ] Tool
- [ ] Policy
- [ ] State / Checkpoint
- [ ] Evidence
- [ ] Business Service
- [ ] UI
- [ ] Infrastructure

## 修改前行为

## 修改后行为

## 保持不变的安全边界
- [ ] 未扩大模型数据库权限
- [ ] 未绕过人工确认
- [ ] 未允许跨用户访问
- [ ] 写操作仍有幂等、权限和事务校验

## 协议变化
- Harness version:
- Prompt version:
- Toolset version:
- Policy version:
- Checkpoint schema:
- Database migration:

## 验证
- 确定性测试：
- Agent Eval：
- 安全测试：
- 恢复测试：

## 指标对比
- 任务成功率：
- 工具误用率：
- 无证据回答率：
- 平均调用数：
- P95 延迟：
- Token 成本：

## 风险与回滚
- 风险：
- 功能开关：
- 回滚步骤：

## 文档
- [ ] CHANGELOG
- [ ] ADR
- [ ] 工具目录
- [ ] Eval Case
- [ ] 部署说明
```

代码评审时，评审人不只检查代码质量，还要检查：

- 是否把代码问题错误地修成 Prompt 问题；
- 是否引入无法关闭的新行为；
- 是否改变安全不变量；
- 是否有旧 Checkpoint 兼容问题；
- 是否可以通过 Eval 证明改善；
- 是否增加不必要的上下文和 Token；
- 是否记录了版本和回滚方式。

## 8. 测试金字塔

Agent 系统需要四层测试。

### 8.1 确定性单元测试

测试：

- 工具参数；
- 权限；
- 金额；
- 库存；
- 版本；
- 幂等；
- 状态机；
- Checkpoint 迁移；
- Claim/Evidence 验证。

这些测试不应调用真实模型。

### 8.2 Harness 路径测试

使用可编程假模型，固定返回：

- 最终回答；
- 单个工具调用；
- 多步工具调用；
- 重复调用；
- 非法参数；
- 未知工具；
- 等待确认；
- 模型超时；
- 写成功后总结失败。

### 8.3 Agent Eval

使用真实或候选模型，验证非确定性行为：

- 能否选择正确工具；
- 是否过度调用工具；
- 是否遵守消歧；
- 是否引用业务证据；
- 是否在缺少能力时安全降级；
- 是否产生误导性确定回答。

### 8.4 生产监控与抽样评审

线上持续统计：

- 任务成功率；
- 人工拒绝率；
- 用户重述率；
- 工具失败率；
- 无证据回答拦截率；
- 安全降级率；
- 平均模型和工具调用数；
- Token 和费用；
- P95 延迟；
- 待确认任务过期率。

自动指标不能完全代替人工轨迹抽样。

## 9. 线上问题处理

发生 Agent 事故时，按以下格式记录：

```text
时间线
影响范围
用户可见症状
Run 和版本分布
直接触发条件
根因
为什么现有测试没有发现
临时缓解措施
永久修复
新增 Eval
新增监控
回滚和恢复情况
```

特别避免：

- 只写“模型偶发问题”；
- 无法关联具体版本；
- 修复后不补 Eval；
- 只依赖人工回忆；
- 把基础设施错误误判为模型能力下降。

## 10. 开源项目学习方法

学习开源 Agent 项目是有价值的，但应采用“问题驱动学习”，而不是“框架驱动重写”。

### 10.1 LangGraph

重点学习：

- Thread 与会话隔离；
- Checkpointer；
- Interrupt/Resume；
- Durable Execution；
- 短期状态与跨 Thread Store 分离；
- 状态图局部测试；
- 副作用的幂等和可恢复性。

适合借鉴到当前项目：

- `AgentCheckpoint`；
- 人工确认恢复；
- Checkpoint Schema；
- Conversation 与长期业务记忆分层。

不建议当前直接迁移整个 Java Harness。

### 10.2 SWE-agent

重点学习：

- Trajectory；
- 每一步 Action/Observation 记录；
- 实验结果目录化；
- 不同配置对同一任务的结果比较。

适合借鉴到当前项目：

- `AgentRunEvent`；
- Eval 结果保存；
- 回归轨迹重放。

### 10.3 smolagents

重点学习：

- 简单 MultiStepAgent；
- ToolCallingAgent；
- 工具参数校验；
- 每一步工作记忆；
- Prompt 和 Tool 模板组织。

适合用于对照当前自研循环，不建议引入代码执行模式。

### 10.4 OpenHands

重点学习：

- Agent 与 Runtime 分离；
- Event Stream；
- Action/Observation 解耦；
- Sandbox；
- 多会话 Runtime 生命周期。

当前五金店 Agent 不需要任意 Shell、浏览器或代码执行，因此只学习事件模型和隔离思想，不照搬完整 Runtime。

### 10.5 Anthropic Agent/Claude Code 公开经验

重点学习：

- 先简单后复杂；
- 工具优先优化；
- Context Engineering；
- 真实失败转 Eval；
- 长任务用结构化产物跨会话交接；
- 每次只增量推进一个可验证目标；
- Canary、监控和事故复盘；
- 模型升级后重新验证 Harness 中的旧假设。

Claude Code 的公开仓库适合观察 Changelog、Issue 和用户反馈，但不能假设其完整内部实现都已开源。

## 11. 当前项目的推荐路线

### 第一阶段：先建立治理基础

- 新建 Agent Changelog。
- 建立 ADR 目录。
- 建立 Agent Eval 目录和格式。
- 为 Run 增加 Harness/Prompt/Toolset/Policy 版本。
- 建立 Agent PR 模板。

### 第二阶段：拆分 Harness

- 提取 `ContextBuilder`。
- 提取 `CheckpointStore`。
- 提取 `RunLifecycleService`。
- 保持现有行为不变，先完成结构重构和回归测试。

### 第三阶段：强化业务事实

执行《Agent 业务事实与记忆强化规范》：

- Evidence；
- FactPolicy；
- ClaimValidator；
- 动态数据强制查询；
- 附件按需读取。

### 第四阶段：完善评测和灰度

- 将真实失败持续加入 Eval Registry。
- 保存基线和候选版本报告。
- 使用功能开关灰度。
- 建立生产指标和人工抽样。

### 第五阶段：再评估是否需要框架或多 Agent

只有出现以下明确问题时再评估：

- 单 Agent 无法稳定完成需要独立规划和验证的长任务；
- 状态图和分支增长到自研维护成本过高；
- 需要复杂的跨服务 Durable Execution；
- 需要独立 Runtime 或沙箱；
- Eval 证明多 Agent 明显提升质量且成本可接受。

## 12. 是否做成 Skill

这份方法论本身更适合保存在知识库，因为它包含：

- 原则；
- 架构判断；
- 团队制度；
- 记录模板；
- 发布和复盘方式。

Skill 更适合执行可重复、输入输出清晰的流程。未来可以从本文档拆出以下 Skill：

### `agent-change-review`

输入一个 Agent PR，自动检查：

- 是否更新版本；
- 是否增加 Eval；
- 是否改变安全不变量；
- 是否存在 Checkpoint 兼容问题；
- 是否填写回滚方案。

### `agent-incident-to-eval`

输入一条失败 Run，自动生成：

- 问题分类；
- 最小复现；
- Eval YAML；
- 建议新增监控；
- ADR 是否需要更新。

### `agent-release-audit`

发布前检查：

- Changelog；
- ADR；
- Eval 报告；
- 数据库迁移；
- 功能开关；
- 灰度和回滚方案。

在团队流程稳定前，不建议过早把方法论整体封装成自动化 Skill，否则会把尚未验证的流程固化下来。

## 13. 团队应长期坚持的不变量

1. 模型不直接访问 Repository 或数据库。
2. 模型不能决定自己的权限。
3. 写操作必须有确定性业务校验。
4. 高风险操作不能绕过人工确认。
5. 动态业务值不能仅来自聊天历史。
6. 每个线上失败应尽量转化为 Eval。
7. 每次重要架构变化都要有 ADR。
8. 每次发布都要有版本、指标和回滚方式。
9. 新增复杂组件前必须证明简单方案不足。
10. 模型升级后必须重新验证 Harness 的旧假设。

## 14. 参考资料

- [Anthropic: Building Effective AI Agents](https://www.anthropic.com/engineering/building-effective-agents)
- [Anthropic: Writing effective tools for AI agents](https://www.anthropic.com/engineering/writing-tools-for-agents)
- [Anthropic: Effective context engineering for AI agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Anthropic: Effective harnesses for long-running agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- [Anthropic: Demystifying evals for AI agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)
- [Anthropic: A postmortem of three recent issues](https://www.anthropic.com/engineering/a-postmortem-of-three-recent-issues)
- [Claude Code Changelog](https://github.com/anthropics/claude-code/blob/main/CHANGELOG.md)
- [LangGraph Persistence](https://github.com/langchain-ai/docs/blob/main/src/oss/langgraph/persistence.mdx)
- [LangGraph Interrupts](https://github.com/langchain-ai/docs/blob/main/src/oss/langgraph/interrupts.mdx)
- [SWE-agent Trajectories](https://github.com/SWE-agent/SWE-agent/blob/main/docs/usage/trajectories.md)
- [Hugging Face smolagents](https://github.com/huggingface/smolagents)
- [OpenHands Architecture](https://github.com/OpenHands/OpenHands/blob/main/docs/architecture.md)

## 15. 与其他项目文档的关系

- 本文档回答“团队怎样长期演进和治理 Agent Harness”。
- [`02-Harness技术设计.md`](./02-Harness技术设计.md) 回答“当前 Harness 如何设计”。
- [`04-MVP开发计划.md`](./04-MVP开发计划.md) 回答“MVP 如何分阶段交付”。
- [`06-Agent业务事实与记忆强化规范.md`](./06-Agent业务事实与记忆强化规范.md) 回答“如何强化业务事实、证据和记忆边界”。

本文档属于长期方法论。具体架构事实发生变化时，应更新对应技术设计和 Changelog，不应把所有细节不断堆入本文档。
