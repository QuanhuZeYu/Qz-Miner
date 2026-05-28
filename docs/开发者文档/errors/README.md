# 错误记录索引

## [20260528]-连锁会话与掉落缓冲耦合导致概率吞掉落

- 文档：`docs/开发者文档/errors/ERROR-20260528-session-drop-buffer-coupling.md`
- 关联问题：`GitHub Issue #232`
- 摘要：旧实现将掉落缓冲绑定在 `ChainSession -> ChainRuntimeState`，导致会话提前清理、重启或生命周期切换时，已收集但尚未释放的掉落可能被误清空，表现为连锁/爆破模式概率吞掉落。

## [20260528]-全项目代码框架设计风险排查

- 文档：`docs/开发者文档/reviews/REVIEW-20260528-代码框架设计风险排查.md`
- 摘要：对全项目（chain/ compat/ network/ mixins/ event/ parallel/ client/）进行系统性设计风险排查，发现 5 项严重风险、9 项高风险、17 项中风险、11 项低风险，覆盖线程安全、内存泄漏、网络攻击面、GPU 资源管理、反射脆弱性等维度。
