# GT 线缆替换执行模型

> 控制论定位：反馈层·决策。本文件承载 GT 线缆（`ChainMode.SPECIAL`）连锁替换执行模型的架构取舍。
> 关联：`NORTH_STAR.md`《偏离登记》`D-GTCABLE-ATOM`、错误预防通则「GT 线缆跨 tick 电压混压」、错误详情 `errors/ERROR-20260706-cable-cross-tick-voltage-mix.md`。

## 背景

GT 线缆承担电压/电流分级（ULV/MV/HV/EV/IV/LuV/... 等级）。一次"换线缆"操作可能牵涉一条链路上数十甚至上千根线缆。GT 电流过载检测在每 tick 网络重算时执行：若链路中并存不同电压线缆，则低压段会被高压电流烧毁，并波及所连接的 GT 机器爆炸。

旧执行模型（v2 流式）按 `maxBreakPerTick`（默认 64）+ 50ms 节流戳逐 tick 消费 `ChainExecutionContext` 队列。这会在线缆替换场景产生"前 N 根已是新电压、剩余仍为旧电压"的中间态——下一个 GT 网络重算 tick 即触发混压爆炸。

## 核心结论：B1 + B2 + B3 三道闸门

GT 线缆替换必须满足「单 tick 内原子替换完整链路」。落地为三道闸门，全部装配在 `ChainExecutionEventBridge.consumeContext` 的 GT 分叉（`shouldWaitForPlannerCompletion=true`）：

- **B1 等规划完成门**：`context.isPlanningComplete()` 为 false 时直接 `return`，等下一 tick 再判。worker 完成遍历并 `markPlanningComplete` 后才放行。避免流式边搜边替换产生瞬时混压。
- **B2 单 tick 原子执行**：进入执行后 `while` 到队列空，绕过 `maxBreakPerTick` 与 50ms 节流戳。整个链路在当前 tick 内一次性替换完。异常 per-target catch（F4 防护），best-effort 继续不崩 drain 帧。
- **B3 预校验放行门**（`precheckCableReplacement`）：
  1. 主手仍是线缆（防会话锁与主手不一致）
  2. 链路目标数 ≤ `Config.cableReplaceMaxPerTick`（默认 1024，与 `chainMaxBlocks` 对齐）
  3. 背包同种线缆总数 ≥ 链路目标数
  
  任一失败：聊天提示玩家原因 + 取消连锁 + 清会话锁，**不放行执行**。

## 安全约束

- 电压不匹配爆炸/烧毁是 GT 线缆替换的硬安全边界，违反即不可恢复地损坏玩家资产。
- `cableReplaceMaxPerTick` 是预校验上限：超过此值的链路宁可拒绝也不冒险单 tick 卡爆主线程。
- 部分失败策略：best-effort + 聊天提示成功/失败根数，不因单根失败整体回滚（已成功的替换无法回滚）。

## 偏离登记

`NORTH_STAR.md`《偏离登记》`D-GTCABLE-ATOM`：GT 线缆替换显式牺牲「不卡主线程 tick」信条换取「单 tick 原子安全」。代价是极端大链路（接近 1024 根）可能逼近 50ms tick 预算，由 `cableReplaceMaxPerTick` 兜底。未来若 GT API 支持暂停网络重算，可探索「多 tick 冻结电压」方案回填此偏离。

## 物品来源（Commit 1 已落地）

- 替换种类基准：主手线缆 `metaTileId`（`GregTechCableReplaceActionExecutor.findLockedCableSlot` 首次锁定进 `GregTechCableSessionState`）
- 消耗顺序：主手外正序（slot 0→8→9→35）同种优先 → 主手最后兜底
- 主手保护：`GregTechCableSessionState.LOCKED_MAIN_HAND_SLOT` 双锁，返还旧线缆时跳过主手 slot，避免旧线缆占用主手致类型锚点错乱
- 旧线缆去向：主线程并入已有堆叠 → 空槽 → 玩家级掉落缓冲；不直接无保障生成实体，释放失败仍按 I5 回填。

## 否决方案

- **方案 Y（多 tick 流式 + 每根替换后立即 causeCableUpdate）**：否决。GT 网络重算成本高，且每根替换后的瞬时网络图仍含混压，无法保证下 tick 重算前不被另一段过载反向波及。
- **方案 Z（多 tick 冻结 GT 电压检测）**：暂搁置。需要 GT API 暴露"暂停网络重算"钩子，当前 GTNH 2.9.0-beta-1 无此能力；待 GT API 支持后可作为 D-GTCABLE-ATOM 偏离的回填方案。

## 演进

- **2026-07-06 初版**：Commit 1（物品来源简化，a9866e3）+ Commit 2（B1+B2+B3 安全核心 + §8 偏离登记 + P2 顺带修）落地。`shouldWaitForPlannerCompletion` 由死代码转为执行分叉开关，`GregTechCableReplaceActionExecutor` 覆盖为 true 触发 GT 单 tick 原子路径。
- **跨 2.8/2.9 收口**：GT 访问改为完整反射 profile，任一类型、方法或字段缺失即关闭能力；替换/回滚均恢复 meta/base 两侧连接，返还物移交主线程执行器并接入玩家级掉落缓冲。
