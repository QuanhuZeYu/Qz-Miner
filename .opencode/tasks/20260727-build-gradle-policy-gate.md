# 任务：补强 build Gradle 授权门禁并修复任务归档

## 元数据
- ID：`20260727-build-gradle-policy-gate`
- 状态：`DONE`
- Owner：OpenCode 内置 `build`
- 创建日期：2026-07-27
- 开始日期：2026-07-27
- 基线分支：`chore/python-command-policy`
- 基线 HEAD：`b330c33bdd86f7d1a3e74608d878568b746cebeb`

## 目标
- 保留 `b330c33b` 已完成的 `build` wrapper/双基线负向门禁修复。
- 把 `.opencode/tasks/20260727-build-persistent-mode.md` 恢复为迁移提交 `d72a688a` 中的完整 DONE 记录。
- 将门禁纠偏范围、证据和结果独立保存在本任务，不再覆盖已完成任务。

## 非目标
- 不修改门禁代码、业务代码、Gradle 协议、治理规则或原迁移提交。
- 不运行真实 Gradle、client/server、双基线、merge 或 push。

## 已确认设计
- `DONE` 任务是情景档案，不重新进入 ACTIVE；后续修正必须建立新任务。
- 原迁移任务恢复为 `d72a688ab7b1fac57d2e37b8fcadb5f8d472017a` 中该文件的精确内容。
- 本任务记录先前门禁 P1、`b330c33b` 修复及任务归档 P1 的关闭证据。

## 写集
- `.opencode/tasks/INDEX.md`
- `.opencode/tasks/20260727-build-persistent-mode.md`
- `.opencode/tasks/20260727-build-gradle-policy-gate.md`

## 验收
- A1：原迁移任务与 `d72a688a` 中的版本一致，保持完整原写集、验收和结果。
- A2：本任务独立记录门禁修复范围、验证和两个复审结论，不篡改历史任务。
- A3：索引同时登记两个 DONE 任务，二者唯一下一步均为“无”。
- A4：最终 diff 只修改本任务写集，不触碰已经通过复审的门禁代码。

## 风险
- R1：手工重述原任务可能继续漂移，必须从 immutable commit `d72a688a` 读取并精确恢复。

## 进度与证据
- `d72a688a` 完成默认 build 与持久任务迁移。
- 首轮独立复审发现环境门禁漏匹配实际角色名 `build`。
- `b330c33b` 已补强 wrapper/双基线负例；增量复审确认门禁 P1 关闭且合法 `gradlew.bat build` 未被误伤。
- 同一增量复审发现原 DONE 任务被覆盖写集，违反完成态归档规则，因此建立本任务纠偏。
- 已从 immutable commit `d72a688ab7b1fac57d2e37b8fcadb5f8d472017a` 精确恢复原迁移任务；`git diff d72a688a -- <原任务路径>` 无输出。
- 已核对完整工作区差异：仅本任务三个授权写集有改动，`scripts/check-agent-environment-ownership.ps1` 等门禁代码未修改。
- 静态验证：`git diff --check` 无输出；`git status --short --branch` 仅列出本任务三个写集。

## 验证
- 对比原迁移任务与 `git show d72a688ab7b1fac57d2e37b8fcadb5f8d472017a:.opencode/tasks/20260727-build-persistent-mode.md`。
- `python scripts/run-agent-command.py -- git diff --check`
- `python scripts/run-agent-command.py -- git status --short --branch`
- 不运行真实 Gradle或运行态。

## 唯一下一步
- 无。

## 结果
- 恢复文件：`.opencode/tasks/20260727-build-persistent-mode.md` 与 `d72a688ab7b1fac57d2e37b8fcadb5f8d472017a` 中版本逐字一致，原完整写集、验收、结果与迁移提交信息均已恢复。
- 独立记录：本任务保留首轮复审发现的 `build` 角色漏检 P1，以及增量复审确认 `b330c33b` 关闭门禁 P1、合法 Gradle `build` 任务未误伤，同时指出 DONE 归档覆盖 P1 的结论；原 DONE 任务不再承载后续纠偏历史。
- 索引：原迁移任务与本任务均登记为 `DONE`，唯一下一步均为“无”。
- 验证：原任务 commit 对比、`git diff --check`、`git status --short --branch` 与完整 diff 写集核对通过；未执行真实 Gradle、client/server、双基线、CI 或运行态。
- 影响边界：只修改三个任务归档写集；未修改门禁代码、业务代码、协议、治理规则或发布事实，I1-I10 不适用且未受影响。
- 残余风险：无；归档漂移风险已由 immutable commit 精确对比关闭。
- 提交信息：`[Docs]: 修复 build 门禁任务归档`。
