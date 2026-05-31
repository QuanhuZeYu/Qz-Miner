# 错误记录

本文件作为错误记录索引。详细问题分析存放在 `docs/开发者文档/errors/` 目录下。

## 记录规则

- 文件命名：`ERROR-YYYYMMDD-简述.md`
- 索引使用二级标题 `## [日期或编号]-[错误简述]`
- 每条记录至少包含：错误现象、触发场景、根本原因、修复方案、预防措施

## 索引

- [`ERROR-20260528-session-drop-buffer-coupling.md`](ERROR-20260528-session-drop-buffer-coupling.md) - 旧实现将掉落缓冲绑定在 `ChainSession -> ChainRuntimeState`，导致会话提前清理、重启或生命周期切换时，已收集但尚未释放的掉落可能被误清空，表现为连锁或爆破模式概率吞掉落。
