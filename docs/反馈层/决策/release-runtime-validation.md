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

## 5.0.22 一次性候选风险接受与发布门

用户已明确授权将 `5.0.22` 作为完整候选推进，并接受真实 client/dedicated、HUD/预览、连续工具接替、空手/模组方块、GT 矿石/线缆、LootGames 与掉落运行态仍为 **INCOMPLETE** 的一次性风险。该授权只适用于 `5.0.22`，不自动成为未来版本的发布政策；上述运行态不会因自动化测试、编译、构建或发布流程成功而改写为通过。

本候选的功能提交范围固定为 `96398ec`（统一连锁采掘能力与工具预览）、`189d6e4`（GTNH `2.9.0-beta-2` 适配）和 `7aaa3ed`（双基线 CI/exact-SHA 发布门）。默认 GTNH 基线为 `2.9.0-beta-2`，最低支持 `2.8.4`；Qz-UILib 要求 `4.6.0+`，本次基于公开 `4.6.1`。既有 `5.0.19`、`5.0.20`、`5.0.21` 的 tag identity 与历史发布事实保持不变。

`5.0.22` 的发布状态按以下三阶段推进：

1. **候选未发布**：创建 tag 前，最终 feature SHA 的双 GTNH 基线 matrix、最终主线 merge SHA 的双基线 matrix 必须全绿，并核对 exact-SHA 关系及 `5.0.22` tag 不存在。任一项未完成都不得创建或推送 tag。
2. **tag 已推送待验收**：tag 前门全绿后，才可创建并推送不可移动的 annotated tag。tag push 触发 workflow 后，必须验收 tag workflow 核心步骤、annotated tag identity、公开 GitHub Release 正文与 draft/prerelease 状态，并下载 `qz_miner-5.0.22.jar`、`qz_miner-5.0.22-dev.jar`、`qz_miner-5.0.22-sources.jar` 核对身份与 SHA-256。上述 tag 后验收不是可选项；任一项未完成或失败，都不得标记为发布闭环。
3. **已发布闭环**：仅当前述 tag 后验收全部通过，才可将 `5.0.22` 写为已发布闭环；tag 推送成功本身不等于发布完成。

本次风险接受不替代任何 tag 前门或 tag 后验收，也不改变真实运行态仍为 **INCOMPLETE** 的状态。
