# 控制包协议（已弃用）

`qz-control-envelope/v1`、`.opencode/control-envelope.json` 及其写前、写后和审查机械门禁已经弃用，不再是任务实施或复审的前置条件。

旧协议中的 `allowedWrites`、审查死区和“第 5 次”停止规则一并失效；当前写集与复审要求只以活动任务单为准。

当前流程以中文 Markdown 活动任务单 `.opencode/task.md` 为唯一载体。任务单格式、生命周期和派发方式见 [`TASK-BRIEF.md`](TASK-BRIEF.md)，完整编排纪律见 [`SUBAGENT-ORCHESTRATION.md`](SUBAGENT-ORCHESTRATION.md)。

历史脚本 `scripts/check-agent-control-loop.ps1` 仅保留用于旧协议诊断和自测，不授予活动流程语义，也不得作为写盘、提交或复审门禁。
