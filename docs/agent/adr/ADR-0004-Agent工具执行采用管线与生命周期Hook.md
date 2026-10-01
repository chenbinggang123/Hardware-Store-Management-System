# ADR-0004：Agent 工具执行采用管线与生命周期 Hook

- 状态：已采用
- 日期：2026-10-01

## 背景

原 `AgentHarness` 同时承担模型循环、运行记录持久化、工具注册、风险策略、审批预览、工具执行和 Trace 记录。继续增加商品、采购、Excel 和订单工具会使主循环频繁变化，任何横切能力也容易侵入业务编排。

## 决策

保持一个小而稳定的有限模型循环，并将执行路径拆成以下边界：

1. `AgentHarness`：模型调用、步数限制、重复调用检测、暂停与恢复。
2. `AgentToolRegistry`：工具发现和白名单注册。
3. `AgentPolicyEngine`：风险分级与是否需要人工确认。
4. `AgentToolExecutor`：工具准备、审批预览和实际执行的唯一入口。
5. `AgentLifecycleHook`：执行前、成功后和失败后的横切扩展点。
6. `AgentRunStore`：运行状态的持久化边界。

执行顺序为：

```text
模型请求工具
  -> Registry 查找
  -> PreToolUse Hook
  -> Policy 判断
  -> 需要审批：生成预览并暂停
  -> 无需审批或已批准：执行工具
  -> PostToolUse / ToolError Hook
  -> 结果返回模型循环
```

## 扩展规则

- 新增业务能力时优先增加独立 `AgentTool`，不得在 `AgentHarness` 中按工具名编写业务分支。
- 权限、审计、指标、脱敏、限流等横切逻辑实现为 Hook 或策略组件。
- Hook 不负责核心业务写入，不改变工具返回值，不吞掉原始工具异常。
- `PreToolUse` 可拒绝执行；`PostToolUse` 和 `ToolError` 故障必须隔离，不能把已成功的业务写入伪装成失败。
- 正式写操作仍由工具调用 Service；工具和 Harness 都不能直接绕过业务事务。
- Hook 顺序使用 Spring `@Order` 声明；Trace 默认最后执行。
- 新增 Hook 必须覆盖成功、失败和审批暂停三种路径的测试。

## 结果

新增工具不再要求修改模型循环；Trace 已从 Harness 移入 Hook。首阶段不改变模型提示词、风险等级、审批卡片和业务接口，因此可用现有回归用例验证兼容性。

代价是执行链多了一层抽象，调试时需要同时查看 Harness、Executor、Policy 和 Hook；通过强类型上下文、统一 Trace 和小范围单元测试控制复杂度。
