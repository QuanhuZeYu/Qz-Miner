# 预览 origin 锁定晚于本地破坏

## 错误现象

ARMED 预览已捕获 origin A 后，本地成功破坏 A；若服务端 PLANNING 快照尚未投影，下一次客户端 END tick 会把转向后的 B 或空准星当成新目标，清除 A 的 frozen seed。迟到的 active phase 随后只能锁住错误目标。

## 触发场景

- 当前预览 target、`BlockSeedSnapshot.origin` 与 world 均为 A。
- `PlayerControllerMP.onPlayerDestroyBlock(A)` 返回 true，A 已在客户端变为空气。
- `ClientPhaseProjection` 仍停在同 generation 的 ARMED/IDLE，准星在下一次采样前转向 B 或为空。

## 根本原因

预览锁定只读取异步 phase 投影，没有消费更早同步可见的“本地成功破坏当前 origin”事实。缺陷最早由 `429a83595506593a083ef3e79a2f62e30c522a1e` 引入；`00d21576ad46725226173115fb2af791f099d9b3` 迁移 phase 权威、`96398ec7d3696168dd9a66a3e5a8b59396818064` 增加 frozen seed 后仍保留窗口。`27465374eb573ba0031fd6a34f407dca1953d49c` 未改 controller/projection blob，不是根因，只可能改变暴露时机。

## 修复方案

成功破坏 Mixin 在 true RETURN 内先把坐标同步通知 `ChainPreviewController`，再保持自动工具通知。控制器只在 active target、frozen seed origin、preview/current world 与坐标全部一致时建立本地 `PreviewOriginLease`，不回读已破坏方块，也不发送网络或切 phase。

租约在同 generation ARMED/IDLE 的投影迟到窗口保守锁定；观察 PLANNING/RUNNING/FINISHING 后由对应同/更新 generation 的 IDLE/ARMED 释放。若 active 快照丢失，generation 推进后的终态也释放。verified-layout 继续复用 A seed且不清租约；完整 reset、松键、preview disabled 与 lifecycle 清理租约。

## 预防措施

- 对本地动作已不可逆、服务端投影异步可见的 observer，明确同步事实的线性化点，不让投影单独覆盖触发窗口。
- 固定“destroy true → 租约 → END tick lock → 准星采样”的结构顺序，并以纯 JVM 状态序列覆盖两种触发时序、active 快照缺失、终态释放与下一 round。
- 静态/JVM/Gradle 只能证明状态与接线合同；真实客户端 A→B 快速转向仍保持 `INCOMPLETE`，不得改写为游戏内通过。
