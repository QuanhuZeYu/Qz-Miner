# 错误记录：掉落释放 world-tick 死循环（35 秒刷 1.8 万行）

## 错误现象

- 用户 abort 大范围规划后，服务端掉落释放陷入死循环：`onWorldTick` 每帧非空、每帧尝试释放、每帧 spawn 失败、每帧 `restoreUnreleasedDrops` 回填 buffer。
- 实机日志约 35 秒内连续刷出 1.8 万行 `[ChainDropCollector] Ready to release aggregated drops ...` 与 spawn 失败 WARN，直到 `ServerStopped` 才结束。
- 同一方块（gen=3 大范围连锁）的约 24 个 stack 在 `ChainPlayerDropBuffer` 中被反复 `drain` → spawn fail → `restoreUnreleasedDrops` 回填 → 下帧再次 `drain` → 再次失败 → 再次回填。
- 玩家最终无法收到这批掉落，且服务端日志被刷屏至 ServerStopped。

## 触发场景

- 用户在配置文件将 `chainMaxBlocks` 改大（`gen=3`，`maxBlocks=256000`）后开了一次超大规模连锁。
- 规划进行中用户主动 abort（关执行窗口），此时 `HarvestDropsEvent` 已聚合多 stack 到 `ChainPlayerDropBuffer`。
- 会话切回 `IDLE`，但 `dropBuffer` 仍非空，进入 `ChainDropCollector.onWorldTick`（`src/main/java/club/heiqi/qz_miner/chain/executor/ChainDropCollector.java:65`）释放路径。
- 玩家当前位置所在 chunk 此刻不可用（abort 后玩家位置/视角变化或 chunk 异步卸载），`World.spawnEntityInWorld` 内部 `!forceSpawn && !chunkExists(...)` 早 return，`releaseAtCoordinates`（`ChainDropReleaseHelper.java:160`）的 `spawnEntityInWorld` 返回 `false`，进入 `restoreUnreleasedDrops` 分支（`ChainDropReleaseHelper.java:177,190`）。

## 根本原因

1. **降级链只接通第一级**：`ChainDropCollector.onWorldTick` 玩家存在分支原本只调 `ChainDropReleaseHelper.releaseAtPlayer`（玩家当前位置）单级，未接通 NORTH_STAR 信条四规划的二/三/四级（重生/出生点 → 已记忆兜底 → discard）。当玩家当前位置 spawn 失败时，没有任何其他降级出口。
2. **失败无条件回填 → 死循环**：`ChainDropReleaseHelper.releaseAtCoordinates`（`ChainDropReleaseHelper.java:160`）在 `spawnEntityInWorld` 返回 false 时调 `restoreUnreleasedDrops`（`ChainDropReleaseHelper.java:199`）无条件把未生成 stack 回填 buffer。下一帧 `onWorldTick` 判 `IDLE && buffer 非空` 再次进入释放路径，再次以同坐标尝试、再次失败、再次回填 → 死循环。
3. **discard 兜底存在但从未被主路径调用**：`ChainDropReleaseHelper.discard(...)`（`ChainDropReleaseHelper.java:149`）方法本已存在（守 NORTH_STAR 信条四终点），但 `onWorldTick` 主路径从未到达它，隐性违反 NORTH_STAR 信条四「四级降级链 + 告警丢弃」——设计意图存在，主路径未接通。
4. **配置项缺合理硬上限**：`chainMaxBlocks` 当前允许 `Integer.MAX_VALUE`，用户随手改大即可触发本 BUG 量级，本错误预防暂不修，留下一轮 polish。

> NORTH_STAR 信条四原文（节录）：掉落释放四级降级链 —— 当前玩家位置 → 玩家重生点/世界出生点 → 已记忆的兜底坐标 → 连续失败达上限告警丢弃，不得无限 restore 重试。

## 修复方案

修复落点两个 commit：`c433706`（玩家存在分支 + forceSpawn + 失败计数 + 诊断）与 `8fa521c`（玩家缺失分支对称补 discard 兜底）。

1. **`ChainDropCollector.onWorldTick` 玩家存在分支接通完整四级降级链**（`ChainDropCollector.java:97-122`）：
   - 一级 `releaseAtPlayer`（玩家当前位置）成功 → `resetDropReleaseFailure()` + continue
   - 二级 `releaseAtRespawnOrWorldSpawn`（玩家在线但当前位置 chunk 未加载等）成功 → reset + continue
   - 三级 `releaseAtRememberedTarget`（`onHarvestDrops` 起手 `rememberRespawnOrWorldSpawn` 预存的兜底坐标）成功 → reset + continue
   - 四级：全链失败 `incrementDropReleaseFailure()` 累加，达 `DROP_RELEASE_MAX_RETRIES=100` 后 `discard(uuid, buffer, "world-tick-release-exhausted")` + reset；未达上限 buffer 仍非空（restore 已回填），下帧重试
2. **玩家缺失分支对称补 discard 兜底**（`ChainDropCollector.java:82-110`，commit `8fa521c`）：
   - 二级 `releaseAtRespawnOrWorldSpawn` + 三级 `releaseAtRememberedTarget` 全链失败时同样 `incrementDropReleaseFailure` 累加，达上限 `discard(uuid, buffer, "world-tick-missing-exhausted")` + reset
   - 防止玩家 LOGOUT 或 `playerManager` 异步延迟场景下 buffer 残留每帧非空无限刷屏
3. **`releaseAtCoordinates` 设 `forceSpawn=true`**（`ChainDropReleaseHelper.java:175`）：EntityItem 构造后、`spawnEntityInWorld` 调用前设 `entityItem.forceSpawn = true`，绕过 `World.spawnEntityInWorld` 内部 `!forceSpawn && !chunkExists(...)` 早 return——这是最常见的 spawn 失败真因。设置后失败真因被压缩为实体上限/远程世界等少数情形。
4. **`ChainPlayerState` 失败计数 + 生命周期收口清零**（`ChainPlayerState.java`）：
   - 新增字段 `dropReleaseConsecutiveFailures`（volatile，独立字段自行管理，守 I10）
   - `incrementDropReleaseFailure()` / `resetDropReleaseFailure()`
   - `clearRuntimeState(String)` 内调 `resetDropReleaseFailure()`（守 I7），玩家重生/切维度/克隆/重新上线后下一轮释放从 0 起算，防跨生命周期脏计数
5. **spawn 失败诊断日志补全**（`ChainDropReleaseHelper.java:178`）：WARN 补 `chunkExists(floor(x/16),floor(z/16))`、`world.loadedEntityList.size()`、`world.isRemote`、坐标、本次失败 stack 数，下次实机能定性 spawn 失败真因。
6. **新增常量 `DROP_RELEASE_MAX_RETRIES = 100`**（`ChainDropCollector.java:29`）：1.7.10 服务端 20 TPS，100 次 ≈ 5 秒，留足降级链恢复窗口又不致长时间吞物品。

## 预防措施

1. **释放路径必须有上限兜底**：任何「先清空再释放、失败回填 buffer」的资源流都必须有连续失败上限（计数 + discard），不能依赖 `restoreUnreleasedDrops` 的无条件回填作为"等下帧重试"的兜底——下帧若仍以同坐标失败，必然死循环。
2. **设计阶段已规划的降级链必须在主路径真正接通**：本错误根因不在设计缺失（信条四早已规划四级降级 + discard 终点），而在 `onWorldTick` 主路径只接通第一级、`discard` 方法存在但从未被调用。新增/重构降级链时，必须以"主路径能到达 discard"作为最低验收线，而非"helper 方法已实现"。
3. **forceSpawn 是本征代价，必须明确接受**：设 `forceSpawn=true` 绕过 `chunkExists` 早 return 后，降级路径可能触发主线程同步 chunk 加载——这是为换取"掉落不丢失"付出的本征代价，已在边界.md 现状锚登记。后续排查 spawn 仍失败时，优先看实体上限/远程世界，而非再怀疑 chunk 未加载。
4. **配置项需合理硬上限**：`chainMaxBlocks` 当前允许 `Integer.MAX_VALUE` 缺陷（用户随手改大即可放大本 BUG 量级）留下一轮 polish，不在本错误修复范围。
5. **失败计数挂玩家级而非缓冲级**：失败计数应跟随玩家生命周期（`clearRuntimeState` reset），挂在 `ChainPlayerDropBuffer` 会被 `buffer.clear/drain` 误清，挂在 `ChainPlayerState` 更稳健。详见 `决策/drop-release-fallback-chain.md`。
