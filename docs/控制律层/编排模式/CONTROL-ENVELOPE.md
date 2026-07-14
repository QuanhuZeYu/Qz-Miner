# 控制包协议（已弃用）

`qz-control-envelope/v1`、`.opencode/control-envelope.json`、哈希绑定、误差向量、固定次数及其写前、写后和审查机械门禁已经弃用，不再是任务实施或复审的前置条件。

当前写集与复审要求只以活动任务单为准，不继承旧控制包的 `allowedWrites`。旧协议的“第 5 次”停止规则已经失效；P2 是非阻断 `observation`，不触发 fixer，只有 P0/P1 阻断。

当前流程只保留轻量审查锚点：验收使用 `A?`，可选风险使用 `R?`。reviewer 按改动类型声明 `review_type=code-change`、`review_type=agent-framework` 或 `review_type=docs-only`；P0/P1 finding 标记 `correction` 并引用适用的 `A?`/`R?`，P2 标记非阻断 `observation` 且不触发 fixer。

reviewer 发现任务单遗漏关键验收或写集时，应返回 `INCOMPLETE/CONTRACT_UPGRADE_REQUIRED`；由主 agent 覆盖更完整的任务单并创建全新 task。该结果表示合同需升级，不与合同完整但实现失败的 P0/P1 `correction` 混同。

当前流程以中文 Markdown 活动任务单 `.opencode/task.md` 为唯一载体。任务单格式、生命周期和派发方式见 [`TASK-BRIEF.md`](TASK-BRIEF.md)，完整编排纪律见 [`SUBAGENT-ORCHESTRATION.md`](SUBAGENT-ORCHESTRATION.md)。

历史脚本 `scripts/check-agent-control-loop.ps1` 仅保留用于人工执行的旧协议诊断和自测，不授予活动流程语义，也不被任何现行门禁调用，不得作为写盘、提交或复审门禁。
