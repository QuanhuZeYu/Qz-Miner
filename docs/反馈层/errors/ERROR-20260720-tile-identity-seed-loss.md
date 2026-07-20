# 正常破坏入口丢失 TileEntity 种子身份

## 错误现象

正常 `BreakEvent` 触发的 same-block 连锁对含 TileEntity 的方块系统性产出 empty plan。同 block、同 metadata、同类 TileEntity 的邻块在 candidate filter 首门即被拒绝，matcher 与采掘能力门未运行。

## 触发场景

`BreakEvent` 发布后由下一 tick 主线程 drain 推进 `BlockBreakObserved → PlanStarted`。原版已在当前 tick 移除 origin；事件只携带 block/metadata，规划桥把 seed TileEntity 固定为 null，而候选坐标仍能读到 TileEntity，形成单边身份并被严格拒绝。该问题适用于任何依赖 TileEntity 身份的普通 same-block 方块，不是单一模组特例。

## 根本原因

事件链没有在 seed TileEntity 尚存的主线程窗口冻结身份，却把后续 World 回读当成可恢复事实。block/metadata 的及时传播修复了 origin 变空气，却遗漏了同一时刻存在的 TileEntity 身份，导致身份快照不完整。

## 修复方案

- 引入状态为 `ABSENT/PRESENT/UNRESOLVED` 的不可变纯值 `TileIdentityToken`；token 不含 TileEntity、World、Block、NBT、Class、坐标或反射成员。
- 在 `BreakEvent` 服务端主线程读取 seed TileEntity 并立即转 token，沿 round 事件、状态机进态广播、seed snapshot 与搜索上下文传播；右键/左键 resolver 同样在主线程捕获。
- 普通 same-block worker 只消费 seed token；候选 TileEntity 从当前只读 World 取得后立即转 token。block/metadata 仍先精确比较。
- 已识别 adapter 读取失败固定为 `UNRESOLVED` 且不降级；未知模组按 runtime type name 保持既有 class 语义。seed `UNRESOLVED` 在 worker 登记前以固定原因 `shadow-seed-tile-identity-unresolved` 取消。
- 保留 GT 线缆特殊模式与客户端预览的既有专用兼容字段；本次不声称完成全 worker 世界快照化。

## 预防措施

- 对异步消费的世界事实逐项检查“事实最后存活窗口”，先写缺失传播的失败测试，再接事件链。
- 为 token 纯值约束、完整身份矩阵、adapter 失败不降级、未知同/异类、无 TileEntity、事件原样传播、legacy `UNRESOLVED` 与 worker 启动前取消建立纯 JVM 回归。
- 生产 matcher 禁止模组名特判；诊断只输出有界状态/策略/类型与固定原因，不输出 identity key、NBT、owner 或对象。
