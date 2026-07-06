# 决策：掉落释放四级降级链与失败上限

## 背景

- NORTH_STAR 信条四早已规划掉落释放四级降级链（当前位置 → 重生/出生点 → 已记忆兜底 → 告警丢弃），但 `ChainDropCollector.onWorldTick` 主路径长期只接通第一级，`ChainDropReleaseHelper.discard` 方法存在但从未被调用。
- 实机因此触发死循环 BUG（35 秒刷 1.8 万行直到 ServerStopped），详见 `errors/ERROR-20260706-drop-release-dead-loop.md`。
- 本决策记录修复后的降级链细化结构、上限取值、forceSpawn 取舍与失败计数挂载位置选型。

## 候选方案

- 仅在玩家存在分支补 discard 上限，玩家缺失分支维持原 `LOG.warn + continue`。
- 玩家存在分支与玩家缺失分支都对称补全降级链 + 失败计数 + discard 上限。
- 改 `ChainDropReleaseHelper.releaseAtCoordinates` 的 `restoreUnreleasedDrops` 为"失败 N 次后丢弃"，把上限下沉到 helper。

## 最终选择

- 玩家存在分支与玩家缺失分支都对称补全降级链 + 失败计数 + discard 上限，失败计数挂在 `ChainPlayerState`，上限常量放在 `ChainDropCollector`。

## 选择原因

### 四级降级链细化结构

两个分支结构对称、出口一致（都抵达 discard 终点），降低后续维护时"一边补一边漏"的风险：

- **玩家存在分支（`EntityPlayerMP` 在线）4 级**：当前位置 → 重生/出生点 → 已记忆兜底 → 连续失败达上限 discard（`ChainDropCollector.java:97-122`）
- **玩家缺失分支（LOGOUT/异步延迟导致 `playerManager` 暂时拿不到 `EntityPlayerMP`）2-3 级 + discard 上限**：重生/出生点 → 已记忆兜底 → 连续失败达上限 discard（`ChainDropCollector.java:82-110`）
- discard reason 分别用 `"world-tick-release-exhausted"`（存在分支）与 `"world-tick-missing-exhausted"`（缺失分支）区分，便于实机日志定性分支。

### `DROP_RELEASE_MAX_RETRIES = 100` 选型依据

- 1.7.10 服务端 20 TPS，100 tick ≈ 5 秒。
- 5 秒窗口：chunk 加载/玩家重新登录/异步 `playerManager` 同步基本能在该窗口内完成，留足降级链恢复机会。
- 又不致长时间吞物品：即使最终 discard，玩家最坏只丢这 5 秒内无法释放的 stack，可接受。
- 选 100 而非更小（如 20=1 秒）是为了给 chunk 异步加载留余量；选 100 而非更大（如 600=30 秒）是为了避免长时间吞物品。

### `forceSpawn = true` 取舍

- `World.spawnEntityInWorld` 内部 `!forceSpawn && !chunkExists(...)` 早 return 是最常见的 spawn 失败真因（abort 后玩家位置 chunk 未加载）。
- 设 `entityItem.forceSpawn = true`（`ChainDropReleaseHelper.java:175`）绕过该检查后，降级路径可能触发主线程同步 chunk 加载——这是为换取"掉落不丢失"付出的本征代价，明确接受。
- 设置后失败真因被压缩为实体上限/远程世界等少数情形，诊断日志（`ChainDropReleaseHelper.java:178`）补 `chunkExists` / `loadedEntityList.size()` / `world.isRemote` / 坐标便于定性。

### 失败计数挂 `ChainPlayerState` 而非 `ChainPlayerDropBuffer` 的理由

- 失败计数应跟随玩家生命周期：玩家重生/切维度/克隆/重新登录后下一轮释放应从 0 起算，`ChainPlayerState.clearRuntimeState(String)` 已是生命周期收口点（守 I7），在那清零最自然。
- `ChainPlayerDropBuffer` 内已有 `FallbackTarget` 记忆（respawn/worldspawn 坐标），但与失败计数语义独立：buffer 表示"要释放什么 + 释放到哪"，失败计数表示"已经连续失败多少次"。
- 挂在 buffer 上会被 `buffer.clear` / `buffer.drain` 误清（每次释放都 drain，drain 后计数即丢，等价于无上限）。
- 挂在 `ChainPlayerState` 上更稳健，且与 `seedDropCaptureArmed` 同属"独立字段自行管理，不经状态机写入口"模式（守 I10）。

### 与 NORTH_STAR 信条四的关系

- 本决策不引入新设计，只是补全信条四的设计意图：discard 终点本就在宪章中，只是 `onWorldTick` 主路径未接通。
- 无 NORTH_STAR 偏离（oracle 已裁决）。

## 影响范围

- `src/main/java/club/heiqi/qz_miner/chain/executor/ChainDropCollector.java`（`DROP_RELEASE_MAX_RETRIES` 常量 + 两分支四级降级链）
- `src/main/java/club/heiqi/qz_miner/chain/executor/ChainDropReleaseHelper.java`（`forceSpawn=true` + 诊断日志）
- `src/main/java/club/heiqi/qz_miner/chain/state/ChainPlayerState.java`（`dropReleaseConsecutiveFailures` 字段 + increment/reset + `clearRuntimeState` 清零）

## 后续注意事项

- 后续若调 `DROP_RELEASE_MAX_RETRIES`，需同步更新 `errors/ERROR-20260706-drop-release-dead-loop.md` 与本决策的"5 秒窗口"论证。
- `chainMaxBlocks` 当前允许 `Integer.MAX_VALUE` 的缺陷（用户改大即可放大本 BUG 量级）留下一轮 polish，不在本决策范围。
- 后续若新增第 5 级降级（如"释放到任意在线 OP 玩家位置"），需更新本决策与 `边界.md` 现状锚，保持两层一致。
