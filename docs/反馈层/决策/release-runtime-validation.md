# 发布运行态验证

## 长期原则

自动化 / CI 与人工运行态分别记录，不能把前者成功表述为后者通过，也不能用人工抽测替代自动门禁。某个版本的风险接受必须明确限定范围，不自动成为后续发布的默认政策。

GTNH 兼容构建的机器数据源为 `gradle/gtnh-baselines.json`，默认出包 manifest 仍以 `gradle.properties` 为唯一权威。branch CI 从清单生成独立 clean-runner matrix，逐项完成精确 GregTech 解析断言与 setup/test/check/build；tag workflow 必须在任何 checkout、构建或 Release 副作用前确认同 SHA、`push`、成功的 `branch-ci.yml` 运行。维护者本地双基线脚本只作可选诊断，不能替代上述发布门，也不授权 agent 执行。

自动化仍不能替代 I8 字节码形状之外的真实掉落、client 与 dedicated server 运行态；缺少这些证据时继续记为 **INCOMPLETE**。branch matrix 的 CI 证据只有实际 push 后才成立，本地通过不得冒充。

## 5.0.19 一次性风险接受

用户已明确授权 `5.0.19` 在人工客户端、dedicated server 与双 GT 运行态矩阵仍为 **INCOMPLETE** 的情况下发布，并接受剩余运行态与诊断盲区带来的风险。该版本现已正式发布；人工运行态未因此变为通过，也不再要求用户补做本版本人工矩阵。

发布证据已经闭环：最终 feature CI [`29559627935`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/29559627935) attempt 2 成功；`5.0重构` merge commit `e3a2243ad266fd7ac33b8f80ff17233291898bad` 的 CI [`29560172747`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/29560172747) 成功；annotated tag `5.0.19` 的 tag object 为 `97392dbdbde86fc2663bb3039b0046913a64e94b`，peeled commit 固定为 `e3a2243`。Tag workflow [`29560573051`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/29560573051) 的 setup、assemble、GitHub Release、Modrinth 与 CurseForge 步骤成功，Maven 因无凭据按预期 skipped。

公开 GitHub Release 为 <https://github.com/QuanhuZeYu/Qz-Miner/releases/tag/5.0.19>，非 draft/prerelease，正文与 `.changelogs/5.0.19.md` 一致。资产核验如下：

| 资产 | 字节数 | SHA-256 |
|---|---:|---|
| `qz_miner-5.0.19.jar` | 746502 | `13754fcd06104c97b97e74435b2b773673d0e0f26eaf30f2326c752c07080bda` |
| `qz_miner-5.0.19-dev.jar` | 741083 | `c548585bf1624d707710713e8ff720ab8f55b6ba4dfc350528f31d365beedda8` |
| `qz_miner-5.0.19-sources.jar` | 429260 | `818d961c7fdc77e5979f460fa185b9d2b0e4ae908a24f3f748010ee47bbb04a7` |

发布后若出现玩家 Issue，玩家只需提供“问题描述 + 对应日志”两项输入；维护者负责分析，并在上下文不足时按需做最小诊断补强，不把人工矩阵转交玩家。该风险接受只适用于 `5.0.19`，后续版本须按各自发布计划重新决定运行态门禁，不得自动继承本次例外。tag 继续固定在 `e3a2243`；tag 之后的文档提交不属于发布制品 SHA，也不得移动该 tag。

## 5.0.20 热修发布与运行态回流

`5.0.19` 真实单人日志确认 EndlessIDs 扩展方块 ID 被旧上限拒绝，以及松键采样失败后客户端清除事务、服务端保留关闭中账本的根因；对应修复已随 `5.0.20` 正式发布。`5.0.19` 与 `5.0.20` 既有 tag/Release 均为不可移动发布事实。

`5.0.20` 发布后的真实单人日志进一步确认两项 P1：合法 `metadata=24902` 仍被 vanilla 0..15 假设拒绝；TAKEOVER 在 pending/current 服务端 anchor 已 exact 后仍受客户端滞后 anchor echo exact 的冗余门影响。旧 release 账本永久失联在该轮日志中未复现，但不据此把完整运行态标记为通过。

第二轮最小修复候选仅将 metadata 域对齐 EndlessIDs 16-bit，并删除 TAKEOVER 冗余客户端 anchor echo 门；协议 v3/int/60-byte framing、服务端主线程库存写权和其他新鲜度/库存安全门不变。固定 reason 诊断用于下一次自然使用日志区分残余拒绝，不把中等置信度归因扩展成更多放宽。

该候选的自动化通过不等于真实 EndlessIDs 与连续 TAKEOVER 运行态通过；后续版本若发布必须创建新 tag 并重新执行自身门禁，不能复用或移动既有发布 tag。

`5.0.19` tag 的 peeled commit 继续固定为 `e3a2243`；`5.0.20` tag 同样保持现有指向，不因本候选修改。

## 5.0.21 热修风险接受

`5.0.20` 的真实单人日志已确认 EndlessIDs 合法 16-bit `metadata=24902` 被旧上限拒绝，以及 TAKEOVER 在服务端 pending/current anchor 已 exact 后仍受冗余客户端 anchor echo 门影响。`5.0.21` 据此将 metadata 域放宽至 `0..0xFFFF`，仅移除该冗余门，并保留其余服务端新鲜度、候选、账本与库存安全门；同时补充 action/gate 单次固定 reason 诊断。该热修现已正式发布。

最终 feature CI [`29575171376`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/29575171376) 与 `5.0重构` merge commit `b264aceb0d3180e80bb67ffc64b6f0dd057c1f4a` 的 CI [`29575675895`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/29575675895) 均在 attempt 1 全绿。Annotated tag `5.0.21` 的 tag object 为 `4e4ddae524c4fd9a68b235ca148e90f65dacd3e5`，peeled commit 固定为 `b264ace`。Tag workflow [`29576125249`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/29576125249) attempt 1 的核心 setup、assemble 与 GitHub Release 成功，Maven skipped；Modrinth/CurseForge 步骤虽为 success，但发布配置为空且无实际外部发布证据，不能宣称已发布到这两个平台。

公开 GitHub Release 为 <https://github.com/QuanhuZeYu/Qz-Miner/releases/tag/5.0.21>，非 draft/prerelease，正文与 `.changelogs/5.0.21.md` 一致。资产核验如下：

| 资产 | 字节数 | SHA-256 |
|---|---:|---|
| `qz_miner-5.0.21.jar` | 749342 | `42d49aad1993fecc6cdf5810c1198af795039a3edd8a1aa2f9dac902f644b735` |
| `qz_miner-5.0.21-dev.jar` | 743983 | `a011788769210213ab37a6ff558964c541168a656300a99197a2057fb254812d` |
| `qz_miner-5.0.21-sources.jar` | 430827 | `36cac92ae746e2aa39d55ee640af3cc2ce52285bdbe55ed28c4843395d9fb6cd` |

修复后的真实 EndlessIDs 与连续 TAKEOVER 运行态仍为 **INCOMPLETE**；自动化、终审、CI 与发布成功均不等于实机通过。用户在知悉验证边界后授权本次发布，该风险接受仅适用于 `5.0.21`，不自动由后续版本继承。后续玩家只需提供“问题描述 + 对应日志”，维护者负责分析并按需最小补强，不把人工测试负担转交玩家。

`5.0.19`、`5.0.20` 与 `5.0.21` 的 tag/Release 均为不可移动发布事实；`5.0.21` tag 固定在 `b264ace`，本次发布后的文档提交不属于发布制品 SHA，也不得移动任何既有 tag。

## 5.0.22 一次性风险接受与最终发布证据

用户已明确接受 `5.0.22` 在真实 client/dedicated、HUD/预览、连续工具接替、空手/模组方块、GT 矿石/线缆、LootGames 与掉落运行态仍为 **INCOMPLETE** 的情况下发布。该版本现已完成公开发布闭环；风险接受只适用于 `5.0.22`，不自动成为未来版本政策，发布成功也不把上述运行态改写为通过。

功能提交范围为 `96398ec`（统一连锁采掘能力与工具预览）、`189d6e4`（GTNH `2.9.0-beta-2` 适配）和 `7aaa3ed`（双基线 CI/exact-SHA 发布门），最终主线 merge commit 为 `3befb0c98e9ec362e9f967f76babb34c0e6d209a`。Annotated tag `5.0.22` 的 tag object 为 `e4e46bc84aa2503b8c9dda610aa854b94c6d63dc`，peeled commit 固定为 `3befb0c`。默认 GTNH 基线为 `2.9.0-beta-2`，最低支持 `2.8.4`；Qz-UILib 要求 `4.6.0+`，本次基于公开 `4.6.1`。

公开 GitHub Release 为 <https://github.com/QuanhuZeYu/Qz-Miner/releases/tag/5.0.22>，非 draft/prerelease，正文明确声明真实运行态 **INCOMPLETE**。三项公开资产的 API 身份、字节数与 SHA-256 如下：

| 资产 | 字节数 | SHA-256 |
|---|---:|---|
| `qz_miner-5.0.22.jar` | 772727 | `829b5f4e50ff48ac391351c2e7ceb8634686c50ae7fcbca29c6f0bd70d0cf1a5` |
| `qz_miner-5.0.22-dev.jar` | 767169 | `e0ffee60b450f9e559cfb317de188e527ca0b40c2106b42f0f6c675a3d8d76bd` |
| `qz_miner-5.0.22-sources.jar` | 439489 | `be4301caadb23d7a1fb388530e997195e84b49c1ba66e68f2cb8fa783c5087be` |

发布后的真实日志暴露三项新问题：接替 pending gate 阻断 CLOSE 导致 round 永久 CLOSING、`planningComplete` 早于 `PlanCompleted` publication 可见，以及 GT 5.09.54.20 线缆 profile 错把 `IRedstoneTileEntity.issueBlockUpdate()` 作为 `IGregTechTileEntity` required。它们只能由 tag 之后的新 hotfix 提交修复，不能移动 `5.0.22` tag 或改写既有 Release；hotfix 自动化与文档验证不替代自动工具事件顺序和两代 GT 线缆真实运行态，当前仍为 **INCOMPLETE**。

后续 hotfix 日志又确认空手 round 约 1793 个目标虽能在约 2 秒内完成规划，却因每目标 TAKEOVER/DECLINE 等待而约每 tick 只消费一个目标，50 tick 后触发 watchdog。tag 后修复仅在服务端增加完整身份限界的 round-scoped 空手回退租约，保留首次真实候选优先与每目标实时采掘权威；不修改 wire、客户端候选、50ms/`maxBreakPerTick`、状态机或 GT 线缆代码。自动化通过仍不能证明真实大批量吞吐，client/dedicated 日志下“一次 DECLINE 后稳定同 key 连续消费且不触发 watchdog”继续为 **INCOMPLETE**。

`5.0.19`、`5.0.20`、`5.0.21`、`5.0.22` 的 tag/Release 均为不可移动发布事实；本次 hotfix 提交不属于 `5.0.22` 发布制品 SHA。

## 5.0.23 标准发布与运行态边界

用户授权的 `5.0.23` 标准完整发布现已闭环。本次功能范围为 `5.0.22` 后六个提交：自动工具 round 关闭收口、规划完成 publication 顺序与 GT `5.09.54.20` 线缆 profile，round-scoped 空手回退租约及其精确失效，通用方块实体 seed token，以及无 TiC 直接依赖的 `HarvestTool` 窄适配。最终 `5.0重构` merge commit 为 `a64d1d7b0ef94559033a773a6059fa3b959b8b9b`；候选 CI [`29795173509`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/29795173509)、merge exact-SHA CI [`29797374514`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/29797374514) 与 release-tags run [`29799955205`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/29799955205) 均 attempt 1 success。

Annotated tag `5.0.23` 的 tag object 为 `e0037f9d1a33620b7bad3c3066cff6d100a21fb5`，peeled commit 固定为 `a64d1d7`。公开 GitHub Release 为 <https://github.com/QuanhuZeYu/Qz-Miner/releases/tag/5.0.23>，非 draft/prerelease，正文与 tagged changelog 字符一致。三项公开资产的实字节与 SHA-256 如下：

| 资产 | 字节数 | SHA-256 |
|---|---:|---|
| `qz_miner-5.0.23.jar` | 798735 | `5769ace3955de7b134505c85583270f202f50a6653749c0eeb98c6f6878bba2e` |
| `qz_miner-5.0.23-dev.jar` | 793051 | `f86ed4911e3b458a74ad3e6576a3934095ee7dd154997e60bb718f2b891767f9` |
| `qz_miner-5.0.23-sources.jar` | 452235 | `a757777330e6a651f9a536da2b761e44a84806d3baa21dc7e9955656c9b9aeaa` |

渠道终态为 GitHub Release success；Maven、Modrinth、CurseForge 均 skipped，不能写成外部发布成功。独立 review 无 P0/P1，仅保留 P2 观察：Release 正文仍含“后续 tag 注入”的发布前时态；该措辞不影响制品身份，本次不反向修改 tagged changelog、历史 Release 或 tag。

实机仍以 `5.0.22-fix-generic-tile-identity.6+fad21dabc2` 为证据来源：TiC Smeltery metadata 2 的 round 1 同时命中 seed/目标 token，`matcher=true`、冻结规划为 `CURRENT_HAND`，`workerConfirmed=8`、`queue=4`、执行消费/成功为 `4/4`，掉落正常释放并自然进入 `FINISHED`。日志中的 `Off-thread read ... serving from snapshot` 是用户确认的设计内 worker 只读 snapshot 诊断，不作为发布风险，也不等于宣称运行日志完全没有 warning。

Round-scoped 空手租约的大批次吞吐/watchdog、GT 两项支持基线的真实线缆替换与 dedicated server 运行态仍缺直接证据，继续记为 **INCOMPLETE**，不由 CI 或发布成功替代。`5.0.23` tag/Release 是不可移动发布事实；发布后的文档提交不属于该制品 SHA，不得移动 tag 或改写历史 Release。

## 5.0.24 标准发布与运行态边界

`5.0.24` 修复 Issue #244 的隧道方向来源：默认 LOOK 在服务端冻结，HIT_FACE 取命中外法线 opposite，客户端预览只读服务端 accepted ACK；旧帧前缀保持可读，新旧混连只安全降级 LOOK。修复提交 `d5c6244acffd544671c5e5731ffa920abea7a857` 的全量 `test/check/build` 与文档门禁已通过，独立 reviewer 无 P0/P1/P2。

发布证据现已闭环：feature 候选 `269399ddcf10fc0bebef090a879ed26ffd4b0849` 的 branch CI [`29832837769`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/29832837769)、`5.0重构` merge commit `350aa102b7f7c498a1212c4a8bc759b3aa787d1a` 的 exact-SHA CI [`29834175117`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/29834175117) 与 release-tags run [`29835307331`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/29835307331) 均 attempt 1 success。Annotated tag `5.0.24` 的 tag object 为 `2c30a93087c65659187b3ed5090c95d2bec97bb6`，peeled commit 固定为 `350aa10`，tag message 为 `[Release]: Qz-Miner 5.0.24`。

公开 GitHub Release 为 <https://github.com/QuanhuZeYu/Qz-Miner/releases/tag/5.0.24>，非 draft/prerelease，正文与 tagged changelog 一致。三项公开资产的实字节与 SHA-256 如下：

| 资产 | 字节数 | SHA-256 |
|---|---:|---|
| `qz_miner-5.0.24.jar` | 814515 | `71649b0416a3d1ebda96f4b30c417a957f15e9678f3bebee8921657d4b6d0739` |
| `qz_miner-5.0.24-dev.jar` | 808813 | `9c0507785e0256fd4be39ac15947bd464cbd585a7d09f0334cc9e23c54388b3e` |
| `qz_miner-5.0.24-sources.jar` | 458519 | `bdd86b356d37ac43dbcf79efb9d1fcfb9076f0990576b25753f2652dcaac44e6` |

渠道终态仅 GitHub Release 有实际发布证据。Maven step 为 skipped；Modrinth 与 CurseForge step 虽为 success，但 `:publish` 无 action、project ID 为空且无远端上传证据，因此只能记为“无实际发布证据”，不能宣称外部发布成功，也不能机械写成 skipped。

Issue #244 已以[唯一回复](https://github.com/QuanhuZeYu/Qz-Miner/issues/244#issuecomment-5034905749)（ID `5034905749`）说明 `5.0.24` 修复、用户确认及剩余边界，随后按 `completed` 关闭，`closed_at=2026-07-21T13:54:35Z`；独立 review 无 P0/P1/P2。

用户仍只用 `qz_miner-5.0.23-fix-issue-244-tunnel-direction.1+af2cd5d390-dirty` 确认 Issue #244 原症状得到修复。该包基于 `af2cd5d` 且带 dirty 改动，不是 `d5c6244` exact-SHA 制品，因此只作为原症状与对应运行路径的实机证据，不能表述为发布 SHA 的完整实机通过。默认 LOOK 的日志与隧道几何交叉覆盖 `+X/+Z/-X/+Y`：round 10/12/14/16 分别执行 `143/143`、`143/143`、`65/65`、`130/130`；round 10/12/16 释放掉落并合法收口，round 14 在工具接替后停止并合法收口。日志没有 source/face 显式 marker。`-Y/-Z`、HIT_FACE 六面、四象限新旧混连和 dedicated server 仍为已接受的 **INCOMPLETE** 发布边界，Issue 关闭与发布成功均不把它们改写为通过。

`5.0.19`、`5.0.20`、`5.0.21`、`5.0.22`、`5.0.23` 与 `5.0.24` 的 tag/Release 均为不可移动发布事实；本次发布后的文档提交不属于 `5.0.24` 发布制品 SHA，不得移动 tag 或改写历史 Release。

## 5.1.0 标准发布与持续运行态边界

用户决定 5.1 stable、prerelease、branch/dirty dev 在合法 5.1 core 内忽略 patch/qualifier 互通，
并允许 remote map 缺 `qz_miner` 时 CLIENT/SERVER checker 双向放行。该决定同时冻结 5.1 family 的
16 个 packet discriminator/Side、现有 framing/protocol/ordinal/code/mask 与 24-path schema；不兼容
变化必须升级新 minor，不能隐藏在 patch 或 dev qualifier 中。

`5.1.0` 标准发布现已闭环。发布候选 `add/version-compatibility-5.1@1cd341f956abfdbce71dedced20962651c66c016`
的 push CI [`30190860784`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/30190860784) 与最终
`5.0重构@be740e196e5cd2c1705e2d27e6689c2a86ee8631` 的 exact-SHA CI
[`30191344675`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/30191344675) 均为
`completed/success`，独立 merge 复审无 P0/P1/P2；两个 CI 的 GTNH `2.8.4`、`2.9.0-beta-2`、
generated/JAR 5.1 dev version 断言与聚合 `build` 均 success。

Annotated tag `5.1.0` object 为 `df396a994e4dbda754ac38149ddeb435721d900b`，message 为
`[Release]: Qz-Miner 5.1.0`，peeled commit 固定为 `be740e196e5cd2c1705e2d27e6689c2a86ee8631`。
Tag workflow [`30191754375`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/30191754375) 为
`completed/success`，exact-SHA gate、setup、默认 GTNH baseline 断言、assemble 与 GitHub Release
均 success；Maven skipped，Modrinth/CurseForge steps 虽为 success，但 project ID 为空且无远端上传证据。

公开 GitHub Release 为 <https://github.com/QuanhuZeYu/Qz-Miner/releases/tag/5.1.0>，非
draft/prerelease，标题为 `5.1.0`，正文与 tagged `.changelogs/5.1.0.md` 字符一致。三项公开资产为：

| 资产 | 字节数 | SHA-256 |
|---|---:|---|
| `qz_miner-5.1.0.jar` | 872436 | `576235783db4f406379286047feab1d623809dc5a9b0be32d7649af68c69df0b` |
| `qz_miner-5.1.0-dev.jar` | 866317 | `591a819444987044f8d63e05ab4515580175b3103bec9bf13c99e36950e0a99a` |
| `qz_miner-5.1.0-sources.jar` | 493640 | `0295bf48c66337a192b4ac83e6ae07277e7814154d0de063e2c26bba40604652` |

主/dev JAR 的 `Tags.class` 均含 exact `5.1.0`。渠道终态仅 GitHub Release 有实际发布证据；Maven
为 skipped，Modrinth/CurseForge 仅有 workflow step success，不能据此宣称外部平台已发布。

用户接受 Miner 自身 clean consumer、Forge status query，以及真实 stable/prerelease/branch/dirty dev
mixed、5.0/畸形拒绝与 missing-mod client/dedicated 两侧运行态不阻断 `5.1.0` 发布；这些边界仍为
**INCOMPLETE**，不能由 parser/JVM、CI、Release 或发布资产改写为通过，每个后续 5.1 patch 也须独立记录。
missing checker true 只放宽 Forge mod-list 检查，不证明无 Mod 对端存在 Qz-Miner channel 或能够安全
接收业务包；除非出现实际日志、复现或用户反馈，不为理论 corner case 预建替身、capability negotiation
或生产级恢复系统。完整合同见 `network-version-compatibility.md`。

`5.1.0` tag/Release 是不可移动发布事实；本次发布后的文档提交不属于该制品 SHA，不得移动 tag 或
改写 tagged changelog 与历史 Release。

## 5.1.1 标准发布与持续运行态边界

`5.1.1` 的业务范围为服务端本地批量自动工具与 preview origin lease。提交
`27465374eb573ba0031fd6a34f407dca1953d49c` 将 ordinary 接替的 physical ledger 收归服务端主线程，
按冻结 policy 与实时目标/库存完成二槽交换、三槽轮转、segment/final restore 及每玩家/tick publication
gate；提交 `096ff9201b8c03f3d7c304aaa88c639750387716` 在本地成功破坏 frozen origin 后建立
generation-aware lease，并在终态、松键、禁用与 lifecycle 收口时释放。发布准备提交为
`31f82b952cc0e54f952d8da2732f78fa440bfd32`；完整候选独立复审为 `APPROVED`，无 P0/P1/P2。

发布候选 push CI [`30247607564`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/30247607564)
绑定 `31f82b952cc0e54f952d8da2732f78fa440bfd32`，为 `completed/success`；matrix prepare
`89918071094`、GTNH `2.9.0-beta-2` `89918101076`、GTNH `2.8.4` `89918101120` 与聚合 build
`89919087270` 均 success。最终 no-ff merge commit 为
`ed5ccc79b201bc77415f64eacd9989dac905e414`，双父为 `15fc3b8`/`31f82b9`，merge tree 与 candidate
tree 相同。Merge exact-SHA CI [`30248326133`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/30248326133)
为 `completed/success`；matrix prepare `89920345838`、GTNH `2.8.4` `89920374718`、GTNH
`2.9.0-beta-2` `89920374766` 与聚合 build `89921449619` 均 success。两次 CI 的 workspace、
GregTech baseline、tests、checks、build 与 generated/main JAR `5.1.1-ci` 版本断言均 success。

Annotated tag `5.1.1` object 为 `e534e84fbee9d4fa070913afb09c7f566ad2d8ba`，message 为
`[Release]: Qz-Miner 5.1.1`，peeled commit 固定为
`ed5ccc79b201bc77415f64eacd9989dac905e414`；tag 未签名是身份事实，不表述为签名通过。Tag workflow
[`30248827749`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/30248827749) 绑定 headBranch
`5.1.1`/headSha `ed5ccc79`，为 `completed/success`；job `89921913237` 的 exact-SHA gate、checkout、
默认 baseline、setup、baseline verify、build 与 Release 均 success。

公开 GitHub Release database ID 为 `360285158`，URL 为
<https://github.com/QuanhuZeYu/Qz-Miner/releases/tag/5.1.1>，于 `2026-07-27T08:14:00Z` 发布；
title/tag 均为 `5.1.1`，非 draft/prerelease，正文经独立脚本与 tagged `.changelogs/5.1.1.md`
字节级精确比较一致。三项公开资产经公开 URL 实际下载计算，且与 API size/digest 一致：

| 资产 | 字节数 | SHA-256 |
|---|---:|---|
| `qz_miner-5.1.1.jar` | 909793 | `c27ccc4946663bd7c12b97a84706c9ea7c8fa93a0c4620bd34470f424cd1b891` |
| `qz_miner-5.1.1-dev.jar` | 903419 | `b763acd3edb5476998b34fd47ddb2ee447462e0952c29a8ca7fab1f8ac62a288` |
| `qz_miner-5.1.1-sources.jar` | 502499 | `fc6cc4262d1d8de2ac6f1e251db2fcb21443d6df7603158a6155314a7f2e81b4` |

本次没有额外下载解析主/dev JAR 内部 `Tags.class`，因此不把内部版本标记写成新增实证。渠道终态仅
GitHub Release 具备独立外部发布证据；Maven step 为 skipped，Modrinth/CurseForge workflow step 虽为
success，但 workflow 未变且没有独立远端制品证据，不能宣称两个平台发布成功。

Miner clean consumer、Forge status query、mixed packaged JAR、真实 client/dedicated、连续二槽/三槽
接替、完整 lifecycle/publication failure 与 preview A→B 游戏内回归继续为非阻断 **INCOMPLETE**；
CI、Release 与 assets 均不能替代这些证据。Qz-UILib 依赖坐标继续为 `4.6.1:dev`，既存 JitPack 发布门
证据继续成立，本次未改变依赖坐标。`5.1.1` tag/Release 是不可移动发布事实；本次写回提交位于 tag
之后，不属于 `5.1.1` 发布制品 SHA，不得移动 tag、改写 tagged changelog 或历史 Release。

## 5.2.0 标准发布与跨世界生命周期回归

`5.2.0` 的核心范围为严格框选爆破、固定 17 个 discriminator 的 5.2 网络合同、服务端本地自动工具精简，
以及跨世界 endpoint/generation 生命周期收口。生命周期修复提交
`7727546dd1a95fa4bb9c64212e09c78cf8548cb6` 统一登录、respawn、跨维度与断线清理，并以合法
`PacketChainConfigSync` 作为客户端 server-ready 证据；依赖发布身份文档修正提交为
`258fcec5afd128b22adbdbef76bfcb91a4ac7618`。完整候选复审无 P0/P1/P2，本地 `compileJava`、定向测试、
`test check build` 与 `git diff --check` 均通过。

最终提交 `258fcec5afd128b22adbdbef76bfcb91a4ac7618` 的 exact-SHA branch CI
[`30357369645`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/30357369645) 为
`completed/success`；GTNH `2.8.4`、`2.9.0-beta-2`、tests、checks、build、generated/main JAR
版本断言与聚合门均 success。Annotated tag `5.2.0` object 为
`0582cfebadc258dad4dd1378f377b596b0d66afb`，peeled commit 固定为该 exact SHA。Tag workflow
[`30357875456`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/30357875456) 为
`completed/success`；exact-SHA gate、默认 baseline、构建与 GitHub Release 均 success。

公开 GitHub Release database ID 为 `361063757`，URL 为
<https://github.com/QuanhuZeYu/Qz-Miner/releases/tag/5.2.0>，于 `2026-07-28T12:15:25Z` 发布；
title/tag 均为 `5.2.0`，非 draft/prerelease，正文与 tagged `.changelogs/5.2.0.md` 一致。三项公开资产的
API 字节数与 SHA-256 如下：

| 资产 | 字节数 | SHA-256 |
|---|---:|---|
| `qz_miner-5.2.0.jar` | 873479 | `44ac998b76926e63674228dba4da941a0afaf4589498ca6f82e9b3444977ab61` |
| `qz_miner-5.2.0-dev.jar` | 866761 | `46213aa11af4e22d475638e7085e9edc50c84890ace340bc5b9fd280e08a51b9` |
| `qz_miner-5.2.0-sources.jar` | 485800 | `bf414672882a17b651e65a9534c2d4c1796ab9349132196801f28768815db935` |

渠道终态仅 GitHub Release 具备独立外部发布证据；Modrinth/CurseForge workflow steps 虽为 success，
但没有独立远端制品证据，不能宣称两个平台发布成功。Qz-UILib 开发/测试依赖已更新为 `4.6.3:dev`；
其 annotated tag object `3b0ad894fb3168e82a6c9075a85eedb86614ee0e`、peeled commit
`16d8c45beaa3c224cc509818fe569607ee94ff65`、workflow `30157047707` 与 canonical dev SHA-256
`01a64ba1f1e7d5102d63413ba5e5cf68ac84586b1bcd3efe0456d641957a9f6c` 已确认。

用户已对跨世界场景完成实机复测，反馈无异常；该证据覆盖本次生命周期问题的目标路径，但不自动扩展为
全部 client/dedicated、鼠标动作取消、AABB/GL、框选执行、游戏内配置命令、第三方库存布局或连续
publication failure 的完整运行态通过，未覆盖范围继续记为 **INCOMPLETE**。`5.2.0` tag/Release 是
不可移动发布事实；本次发布后的文档提交不属于该制品 SHA，不得移动 tag、改写 tagged changelog 或
历史 Release。

## 5.2.1 dedicated server 启动 hotfix

`5.2.0` 发布后的生产 dedicated server 日志在 Mixin 应用阶段稳定失败：
`MixinServerConfigurationManager.beforeRespawn` 无法在 `ServerConfigurationManager` 中找到 MCP selector
`respawnPlayer(EntityPlayerMP,int,boolean)`，随后以 critical injection failure 终止启动。根因为 vanilla
生命周期 Mixin 在类级设置 `remap=false`，发布 JAR 未把标准 MCP 方法名映射到生产 SRG 名。`5.2.0`
tag/Release 保持不可移动，但不能作为 dedicated server 可用版本；修复只能由新 patch 发布。

Hotfix 提交 `86a4f609a3dd14dbce7eccbd07cbf12a18f98e77` 恢复标准 vanilla `respawnPlayer` 与
`onDisconnect` 的 refmap，并为 Forge 三参数登录与 Teleporter 跨维度 overload 显式列出 MCP/SRG
selector。生成 refmap 已确认包含 `respawnPlayer -> func_72368_a` 与 `onDisconnect -> func_147231_a`；
本地定向测试、`compileJava`、`test check build` 与 `git diff --check` 通过，独立复核无 P0/P1/P2。
该 patch 不修改 5.2 已冻结的 packet discriminator/Side、wire、protocol、ordinal、code、mask 或配置 schema。

最终提交的 exact-SHA branch CI
[`30413515183`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/30413515183) 为
`completed/success`；GTNH `2.8.4`、`2.9.0-beta-2` 的 workspace、GregTech baseline、tests、checks、
build、generated/main JAR `5.2.1-ci+SHA` 断言及聚合门均 success。Dev workspace 对备用 SRG selector
给出“target method not found” annotation 是双名 selector 的预期静态提示；同一 injector 的 MCP selector
已匹配并满足 `require=1`。

Annotated tag `5.2.1` object 为 `67811b2d8389f49b034cf2cb49cd987cf6617c42`，peeled commit 固定为
`86a4f609a3dd14dbce7eccbd07cbf12a18f98e77`，message 为 `[Release]: Qz-Miner 5.2.1`。Tag workflow
[`30413907750`](https://github.com/QuanhuZeYu/Qz-Miner/actions/runs/30413907750) 与 job `90456064082`
均为 `completed/success`；exact-SHA gate、默认 baseline、构建与 GitHub Release 均 success，Maven skipped。
Modrinth/CurseForge steps 虽 success，但没有独立远端制品证据，不能宣称两个平台发布成功。

公开 GitHub Release database ID 为 `361460643`，URL 为
<https://github.com/QuanhuZeYu/Qz-Miner/releases/tag/5.2.1>，于 `2026-07-29T01:27:27Z` 发布；
title/tag 均为 `5.2.1`，非 draft/prerelease，正文与 tagged `.changelogs/5.2.1.md` 一致。三项公开资产
经公开 URL 下载计算 SHA-256，且与 API size/digest 一致：

| 资产 | 字节数 | SHA-256 |
|---|---:|---|
| `qz_miner-5.2.1.jar` | 873766 | `79c2f0d4d1c3ac0c6abbf794c6c6f7645394734def80d22832b3644c303dee1d` |
| `qz_miner-5.2.1-dev.jar` | 867045 | `120a34e3c6b929e6dfb572f60316f1d055ae27dd7e9a759e188627e1efa8907b` |
| `qz_miner-5.2.1-sources.jar` | 485830 | `1abe28511ac96725e0fcb6e0365023efa83358555e7c7abef70430586ce10f72` |

修复后 dedicated server 启动、登录、重生、切维度和断线 smoke test 在发布时仍为 **INCOMPLETE**；CI、
refmap、Release 与资产证据不能替代该运行态。`5.2.1` tag/Release 是不可移动发布事实；本次发布后的
文档提交不属于该制品 SHA，不得移动 tag、改写 tagged changelog 或历史 Release。
