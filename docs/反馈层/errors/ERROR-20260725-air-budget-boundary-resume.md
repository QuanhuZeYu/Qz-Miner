# ERROR：空气折扣在配额边界丢坐标或跨片失效

## 错误现象

- 大空区 planning 若仍逐空气扣 1，会过早耗尽单片预算；若简单把空气免费化，又可能在预算、窗口或 deadline 已耗尽后继续偷跑。
- 第 1024 个空气若先推进 cursor/visited/queue 再扣费，扣费失败会丢坐标；若下一片无条件重做，则可能重复入队或重复 confirmed。
- work budget=1 的 Box/Tunnel/Section 若每片先重复扣 shell/slice 入口费，会永远到不了同一坐标。

## 触发场景

- 同一 planning context 跨 step、slice 或 tick 累计 1024 个以上 `Blocks.air`。
- 第 1024 个空气到达时正预算不足，下一片世界由空气变成非空气。
- 已入 frontier 的非空气在取队首前变为空气。
- AREA 大空区使用极低单片预算，shell/slice 正在初始化或中途恢复。

## 根本原因

1. **先推进再扣费**：配额提交与 traverser 状态提交不是同一线性化事务。
2. **每 slice 重置余数**：把累计值放在 `ParallelTickControl`/`ParallelWorkBudget` 或 traverser 临时片状态，无法跨 tick 保存。
3. **入口重复扣费**：shell/slice 初始化没有先持久化“已进入”状态，下一片重复支付相同入口成本。
4. **先 poll 后识别空气**：配额不足时队首已经不可逆移除，无法满足无状态推进。

## 修复方案

- 由 `ChainSearchContext` 独占一个只保存 0..1023 余数的 `PlanningCandidateWorkBudget`，六个 traverser 只走这一入口。
- 每个事务先检查 cancel/`shouldYield()` 再读取世界。余数小于 1023 时提交空气并递增；第 1024 个先 `tryConsumeWork(1)`，成功后才清零并允许调用方推进。
- 返回 `AIR_COMMITTED / NORMAL_COMMITTED / YIELDED / TERMINATED`；只有前两者允许修改 cursor/visited/frontier/queue。队首用 `peek()` 计费，成功后只 poll 一次。
- shell/slice 切换仍收费，但扣费成功后立即保存初始化状态；后续片从坐标游标继续，不重复入口费。matcher、consumer、duplicate、frontier 与 GT 方向解析保持正常预算。

## 预防措施

- 测试固定 1023/1024/1025/2048 商余数、跨 slice、边界扣费失败、空气变非空气、cancel/window、混合非空气与尾余数。
- 六 traverser 同时覆盖 work budget=1 与充足预算输出等价、seed 零探测、AREA 空气后实体、CHAIN/Logging/GT 空气不桥接、排队后变空气只 poll 一次。
- 评审任何新 traverser 时检查：共享 context 计费入口、控制检查早于世界读取、失败路径零持久状态推进、结构工作未被空气折扣吞并。
