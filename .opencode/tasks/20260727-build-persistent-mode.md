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
- `.gitignore`、`.opencode/.gitignore`、`.opencode/opencode.json`。
- `.opencode/agents/*.md`（删除）、旧临时任务入口（删除）、`.opencode/tasks/INDEX.md` 与本任务文件。
- `AGENTS.md`、`NORTH_STAR.md`。
- `docs/控制律层/编排模式/` 下旧编排文档（删除）、`PERSISTENT-WORKFLOW.md`、`TASK-BRIEF.md`。
- `docs/控制律层/Windows-Gradle执行协议.md`、`稳定命令.md`、`项目约定.md`。
- `docs/传感层/门禁脚本说明.md`、`docs/设定值层/硬约束总目录.md`、`docs/反馈层/交接.md`、`docs/反馈层/错误预防.md`。
- `scripts/run-agent-command.py`、`scripts/run-gradle-opencode.py`、`scripts/check-agent-environment-ownership.ps1`、`scripts/check-doc-discipline.ps1`、旧 control-loop 诊断（删除）。

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
- 配置：`.opencode/opencode.json` 已设置 `default_agent: build`；8 个自定义 agent 定义及旧编排、handoff、control-envelope、control-loop 文件已删除。
- 持久化：新增 `PERSISTENT-WORKFLOW.md`、重写 `TASK-BRIEF.md`，新增可提交的任务索引与本结果文件；临时迁移任务入口已删除。
- 安全：Python runner 与 Gradle adapter 已纳入；有限 Gradle 仍只允许 `ACTIVE` 任务经 `qz-gradle-opencode/v1`，环境所有权、禁止并行、运行态和双基线边界未扩大。
- 验证：`git --version` 通过；Gradle adapter `self-test` 返回 `SELF_TEST_SUCCEEDED/ALL_FIXTURES_PASSED`，未调用真实 Gradle；环境所有权 self-test、文档纪律门禁和 `git diff --check` 通过；最终 status 仅含本迁移写集。
- 证据边界：未执行真实 Gradle、client/server、双基线、CI 或运行态，不将静态/自测写成这些证据。
- 残余风险：内置 build 配置需重启 OpenCode 后生效；无独立 reviewer 的上下文隔离，关键发布可另开会话或外部 review。
- 提交信息：`[Chore]: 迁移默认 build 与持久任务工作流`（本任务文件所在提交）。
