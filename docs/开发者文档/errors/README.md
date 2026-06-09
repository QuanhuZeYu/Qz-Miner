# 错误记录

本文件作为错误记录索引。详细问题分析存放在 `docs/开发者文档/errors/` 目录下。

## 记录规则

- 文件命名：`ERROR-YYYYMMDD-简述.md`
- 索引使用二级标题 `## [日期或编号]-[错误简述]`
- 每条记录至少包含：错误现象、触发场景、根本原因、修复方案、预防措施

## 索引

- [`ERROR-20260609-gtnh-29-ore-adapter-api.md`](ERROR-20260609-gtnh-29-ore-adapter-api.md) - GTNH 2.9 beta 将 GT / BW / GT++ 普通矿掉落逻辑迁移到 `gregtech.common.ores.*OreAdapter`，并移除 `BaseMetaPipeEntity.issueClientUpdate()`，导致旧 mixin 与线缆刷新 API 编译失败。
- [`ERROR-20260605-chain-drop-final-target-race.md`](ERROR-20260605-chain-drop-final-target-race.md) - 并行规划线程在执行器消费最后一个目标期间观察到空队列，提前将状态切为 `IDLE` 并清理会话，导致最后一次掉落可能脱离聚合链路。
- [`ERROR-20260601-lootgames-server-client-signature.md`](ERROR-20260601-lootgames-server-client-signature.md) - LootGames 扫雷兼容在服务端使用 `Class#getMethod` 扫描方块类公开方法，触发客户端专属 `IIconRegister` 签名解析，导致 dedicated server 初始化崩溃。
- [`ERROR-20260528-session-drop-buffer-coupling.md`](ERROR-20260528-session-drop-buffer-coupling.md) - 旧实现将掉落缓冲绑定在 `ChainSession -> ChainRuntimeState`，导致会话提前清理、重启或生命周期切换时，已收集但尚未释放的掉落可能被误清空，表现为连锁或爆破模式概率吞掉落。
