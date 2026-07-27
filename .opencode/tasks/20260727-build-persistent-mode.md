# 任务：迁移默认 build 与持久任务模态

## 元数据
- ID：`20260727-build-persistent-mode`
- 状态：`DONE`
- Owner：OpenCode 内置 `build`
- 创建日期：2026-07-27
- 开始日期：2026-07-27
- 基线分支：`chore/python-command-policy`
- 基线 HEAD：`18056ec6148623c4aed59b7f29135cae1e6edf3d`

## 目标
- 保留既有 Python 命令与 Gradle 适配迁移，把仓库改为内置默认 `build` 直接工作。
- 用可提交的 `.opencode/tasks/<id>.md` 与 `INDEX.md` 持久保存任务状态。
- 保持有限 Gradle、安全边界、I1-I10、业务与发布约束不变。

## 非目标
- 不修改业务源码、公共 API、版本、依赖、CI、发布或运行态策略。
- 不执行真实 Gradle、client/server、双基线、merge、push、tag 或 release。

## 已确认设计
- 配置显式指定 `default_agent: build`，删除仓库自定义 agent。
- 采用 L0-L3 记忆、CHAT/DESIGN/EXECUTE 入口及六态任务状态机。
- Gradle 权限绑定 `ACTIVE` 任务中的明确验证清单，唯一入口仍为 `qz-gradle-opencode/v1` Python 适配器。

## 写集
- `scripts/check-agent-environment-ownership.ps1`。
- `.opencode/tasks/INDEX.md`。
- `.opencode/tasks/20260727-build-persistent-mode.md`。

## 验收
- A1：默认 agent 为内置 `build`，无自定义 agent 定义。
- A2：现行治理不依赖旧 subagent、临时 task/handoff 或 control envelope。
- A3：持久工作流完整定义入口、L0-L3、状态机、所有权与写回时机。
- A4：有限 Gradle、环境所有权、禁止并行与运行态边界未扩大。
- A5：Python runner、adapter self-test、门禁与静态检查通过，未运行真实 Gradle。
- A6：提交只含授权迁移写集，任务结果和索引可独立恢复。

## 风险
- R1：无独立 reviewer 时由 build 强制自审完整 diff；关键发布仍可另开会话或外部 review。
- R2：Gradle 授权从角色约束迁为任务状态约束，门禁必须守住 Python 协议唯一入口。

## 进度与证据
- 已核对分支、HEAD 与已有工作区；现有修改均为用户确认应保留的同轮迁移。
- 已建立持久任务、迁移默认 build 治理并删除旧自定义 agent、临时状态与控制诊断。
- 已回读配置、AGENTS、持久工作流、任务规范、Gradle 协议、门禁说明、索引与本任务；专用 Grep 对现行治理文件未发现旧角色或旧路径依赖。
- 独立复审提交 `d72a688ab7b1fac57d2e37b8fcadb5f8d472017a` 发现 P1：环境门禁的受扫描授权文本只匹配单词 `agent`，漏掉迁移后的实际角色名 `build`；现有 self-test 也没有 `build` 负例。
- 已将受限 Gradle 授权主体扩为 `agent` 与角色语义位置的 `build`，并补齐 wrapper、双基线的 `build` 负例及 Gradle `build` 任务合法文本防误报 fixture。

## 验证
- `python scripts/run-agent-command.py -- git --version`
- `python scripts/run-gradle-opencode.py self-test`
- `python scripts/run-agent-command.py repo-script check-agent-environment-ownership --self-test`
- `python scripts/run-agent-command.py repo-script check-doc-discipline`
- `python scripts/run-agent-command.py -- git diff --check`
- `python scripts/run-agent-command.py -- git status --short --branch`
- 专用 Grep 与回读核对现行治理；不运行真实 Gradle或运行态。

## 唯一下一步
- 无。

## 结果
- 基线迁移提交：`d72a688ab7b1fac57d2e37b8fcadb5f8d472017a`。
- 纠偏文件：`scripts/check-agent-environment-ownership.ps1`；门禁现在阻断 `build` 直接调用 wrapper 或获授权运行 `verify-gtnh-baselines.ps1`，且不把 `gradlew.bat build` 的任务名误判为角色。
- 验证：`git --version` 通过；Gradle adapter `self-test` 返回 `SELF_TEST_SUCCEEDED/ALL_FIXTURES_PASSED`；环境所有权 self-test、文档纪律门禁与 `git diff --check` 通过。
- 证据边界：未运行真实 Gradle、client/server、双基线、CI 或运行态；I1-I10 与业务、依赖、发布契约未改。
- 提交信息：`[Fix]: 补强 build Gradle 授权门禁`。
