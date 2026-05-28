# Issue #232 掉落缓冲解耦修复方案

## 问题结论

- 问题编号：`#232`
- 现象：连锁基础模式、爆破矿石模式在 GTNH 2.8.4 中使用镐子、锤子等工具时，存在概率性吞掉落。
- 根因：旧实现将掉落缓冲绑定在 `ChainSession -> ChainRuntimeState` 上，掉落释放又延迟到执行结束后的 `WorldTick`，导致会话提前清理、重启或生命周期切换时，已收集但尚未释放的掉落可能被误清空。

## 修复前链路

1. `BlockHarvestActionExecutor.execute(...)` 调用 `tryHarvestBlock(...)`
2. `ChainDropCollector.onHarvestDrops(...)` 收集 `HarvestDropsEvent`
3. 掉落进入 `ChainRuntimeState.pendingDrops`
4. `event.drops.clear()`
5. `ChainDropCollector.onWorldTick(...)` 在 `IDLE + session 存在` 时统一释放

旧链路的问题在于：掉落释放依赖旧 `session` 存活。

## 修复后链路

1. `HarvestDropsEvent` 仍由 `ChainDropCollector` 统一收集
2. 掉落进入 `ChainPlayerDropBuffer`
3. `ChainSession / ChainRuntimeState` 只维护单次连锁会话，不再保存掉落
4. 执行停止后可直接清理会话运行态
5. 玩家级掉落缓冲独立在 `WorldTick` 或生命周期清理中释放

## 核心设计变化

### 1. 玩家级掉落缓冲

- 类：`src/main/java/club/heiqi/qz_miner/chain/state/ChainPlayerDropBuffer.java`
- 挂载位置：`ChainPlayerState`
- 能力：聚合、合并、提取、记录兜底释放坐标

### 2. 掉落释放助手

- 类：`src/main/java/club/heiqi/qz_miner/chain/executor/ChainDropReleaseHelper.java`
- 职责：
  - 释放到当前玩家位置
  - 回退到重生点或世界出生点
  - 使用已缓存的兜底坐标释放
  - 最终丢弃并告警

### 3. 会话与掉落解耦

- `ChainRuntimeState` 不再持有 `pendingDrops`
- `ChainPlayerState.stopExecutionPreservingDrops(...)` 现在只结束会话运行态
- `ChainDropCollector` 不再依赖 `session` 才能释放掉落

## 生命周期兜底策略

当前固定策略如下：

1. 优先释放到当前玩家位置
2. 若拿不到当前玩家，则回退到该玩家的重生点
3. 若重生点不可用，则回退到世界出生点
4. 若已记录过兜底坐标，则允许在后续上下文缺失时直接释放到该坐标
5. 全部失败后，记录警告并丢弃缓冲，避免脏数据悬挂

## 本轮顺手整治的框架点

- 会话结束不再被掉落缓冲反向绑死
- 状态日志中的 `pendingDrops` 改为读取玩家级缓冲
- 生命周期清理统一走 `ChainStateService.cleanupPlayerState(...)`

## 本轮未处理的问题

- 并行规划线程仍会直接触发部分状态变更
- 网络 handler 仍直接调用状态服务
- 异步世界读取模型未被彻底重构

这些问题已被识别为后续框架整治项，但不纳入本次 `#232` 修复范围。
