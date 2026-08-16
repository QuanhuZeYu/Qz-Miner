# ERROR-20260706 GT 线缆跨 tick 替换中间态电压混压

> **后续演进：** 下文的 `maxBreakPerTick + 50ms` 是问题发生时的普通执行模型。5.3 普通路径已改为 shared soft deadline，但 GT 三道闸门和单 tick 原子 drain 保持不变，并在入场后显式绕过普通 deadline。

## 错误现象

GT 线缆连锁替换时，若链路中存在多根线缆且执行跨越多个 tick，则替换过程中链路上同时存在新旧两种电压的线缆。下一个 GT 网络重算 tick 检测到低压段承载高压电流，触发：

- 低压线缆烧毁（消失/掉落）
- 连接到低压段的 GT 机器爆炸（机器方块被毁，机器内物品掉落）
- 可能连锁波及更大范围的电力网络

## 触发场景

- 玩家手持高压线缆（如 IV 线缆）对一条现有的低压线缆链路（如 LV/MV）执行连锁替换
- 链路目标数 > `maxBreakPerTick`（旧默认 64），如一条 200 根的线缆链路
- 旧执行模型（v2 流式）逐 tick 消费：第 1 tick 替换前 64 根为 IV，剩余 136 根仍为 LV/MV
- 第 1 tick 与第 2 tick 之间的 GT 网络重算发现混压链路 → 爆炸

## 根本原因

v2 流式执行模型（`ChainExecutionEventBridge.consumeContext`）对 `ChainActionExecutor.execute` 的调用按 `maxBreakPerTick` 上限 + 50ms 节流戳控速，对普通方块破坏（CHAIN/AREA/INTERACT）是正确的（保护主线程 tick 预算），但对 GT 线缆替换这类**语义上要求原子完成**的操作是灾难性的——线缆替换不是单纯破坏方块，而是改变链路的电压/电流分级属性，中间态在网络重算视角是非法的。

## 修复方案

GT 线缆替换走专属执行分叉（`shouldWaitForPlannerCompletion=true`），三道闸门保证单 tick 原子：

- **B1**：等 `context.isPlanningComplete()` 才放行，避免流式边搜边替换
- **B2**：`while` 到队列空，绕过 `maxBreakPerTick` 与 50ms 节流戳
- **B3**：`precheckCableReplacement` 预校验链路不超 `Config.cableReplaceMaxPerTick`（默认 1024）+ 背包线缆充足，否则不放行 + 聊天提示

详见决策 `docs/反馈层/决策/gt-cable-replacement-model.md` 与 `NORTH_STAR.md`「GT 线缆替换单 tick 原子执行」。

## 预防措施

- 任何「属性变更型」操作（不仅是破坏方块）若引入执行模型分叉，必须评估其中间态是否对目标系统（GT 网络、IC2 网络、AE 网络…）合法
- 跨 tick 中间态危险的语义必须走单 tick 原子路径，预校验上限兜底防卡 tick
- 通用 shared deadline 只对可跨 tick 完整收口的操作安全，不能假定所有 `ChainActionExecutor` 都适用
- 上溯规则：本类误差已在 `NORTH_STAR.md`「GT 线缆替换单 tick 原子执行」登记为长期已知例外
