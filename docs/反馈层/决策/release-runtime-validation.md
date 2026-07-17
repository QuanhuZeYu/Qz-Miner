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

## 5.0.20 热修风险接受

`5.0.19` 真实单人日志已确认 EndlessIDs 扩展方块 ID 被旧上限拒绝，以及松键采样失败后客户端清除事务、服务端保留关闭中账本的根因。`5.0.20` 修复候选的目标与全量 Gradle 协议验证、文档纪律、事实漂移和环境所有权门禁均已通过，独立终审无 P0/P1/P2。

修复后的真实 EndlessIDs 客户端尚未复测，人工运行态仍为 **INCOMPLETE**。用户已在知悉该状态与 `5.0.19` 缺陷后明确授权发布 `5.0.20` 热修；该授权不等于运行态通过，也不构成后续版本的默认豁免。

发布前后仍须完成以下最终门禁：

- 最终 feature SHA 的 branch CI 成功，并确认运行对象与拟合并提交一致。
- 使用 `--no-ff` 合并后，merge SHA 的 branch CI 成功。
- 在 merge SHA 上创建新的 annotated tag `5.0.20`，核验 tag workflow、公开 Release 正文，以及 main、dev、sources 三项资产。

`5.0.19` tag 必须保持不动，其 peeled commit 继续固定为 `e3a2243`；`5.0.20` 只能使用新 tag，不能移动或复用既有发布 tag。
