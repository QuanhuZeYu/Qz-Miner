# 发布运行态验证

## 长期原则

自动化 / CI 与人工运行态分别记录，不能把前者成功表述为后者通过，也不能用人工抽测替代自动门禁。某个版本的风险接受必须明确限定范围，不自动成为后续发布的默认政策。

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
