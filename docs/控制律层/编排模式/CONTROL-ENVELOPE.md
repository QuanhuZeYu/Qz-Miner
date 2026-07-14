# 控制包协议（已弃用）

`qz-control-envelope/v1`、`.opencode/control-envelope.json`、哈希绑定、误差向量、固定次数及其写前、写后和审查机械门禁已经弃用，不再是任务实施或复审的前置条件。

当前写集与复审要求只以活动任务单为准，不继承旧控制包的 `allowedWrites`。旧协议的“第 5 次”停止规则已经失效；P2 非阻断观察不是审查死区，不触发 fixer，只有 P0/P1 阻断。

reviewer 发现任务单遗漏关键验收或写集时，应返回 `INCOMPLETE`；由主 agent 覆盖更完整的任务单并创建全新 task。

当前流程以中文 Markdown 活动任务单 `.opencode/task.md` 为唯一载体。任务单格式、生命周期和派发方式见 [`TASK-BRIEF.md`](TASK-BRIEF.md)，完整编排纪律见 [`SUBAGENT-ORCHESTRATION.md`](SUBAGENT-ORCHESTRATION.md)。

历史脚本 `scripts/check-agent-control-loop.ps1` 仅保留用于旧协议诊断和自测，不授予活动流程语义，也不得作为写盘、提交或复审门禁。
