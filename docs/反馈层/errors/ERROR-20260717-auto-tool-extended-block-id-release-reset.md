# 自动工具扩展方块 ID 导致松键事务失联

## 错误现象

- Qz-Miner 5.0.19 在启用 EndlessIDs 的环境中，准星指向 ID 大于 4095 的方块时反复出现 `block id/metadata out of range`。
- 已完成 SWAP/FREEZE 的 round 在松键后可长期停留于服务端 `CLOSING ledger=true`，客户端却已回到 `serverRoundId=0`，后续既无 RESTORE/CLOSE，也无法建立新 round。

## 触发场景

1. EndlessIDs 开启扩展方块 ID，目标 ID 落在 4096..16777215。
2. 自动工具 round 已持有服务端库存 ledger，松键边沿捕获 light/context 时目标身份校验失败。
3. facade 将采样异常降级为 `null`，adapter 把这次非生命周期采样失败误当成 lifecycle reset。

## 根本原因

- 自动工具协议把方块 ID 上限硬编码为 vanilla 旧上限 4095，没有对齐项目实际支持的 EndlessIDs 24-bit ID 域。
- 松键采样失败路径调用 `resetForLifecycle()`，清除了客户端 round、swap expectation 与 release/restore 义务；服务端只收到自然关闭事实，仍持有 ledger，双方事务关联永久断开。

## 修复方案

- 将协议合法上限扩展为 `(1 << 24) - 1`，保持正 ID、metadata 0..15、`int` 帧、协议版本 v3 和同版本要求不变；越界异常只记录 `blockId/metadata/max` 安全标量。
- adapter 在松键 light/context 捕获失败时发布 `KeyStateEvent(false, null)`，不再调用 lifecycle reset，并输出单次边沿 marker。
- reducer 让可空上下文的 release 继续推进 `keyDown=false`、round closing 与 close 请求；有 ledger 时保留 pending RESTORE，后续有效 tick 重新捕获，无 ledger 时直接 CLOSE。
- 真实连接/世界生命周期入口仍通过 `ResetEvent` 硬复位，禁止跨连接盲目恢复。

## 预防措施

- 声明兼容 ID 扩展器时，协议、值对象与网络请求必须共享同一 ID 域常量，并覆盖 4096、32767、24-bit 最大值及越界值。
- 区分“事实暂不可采样”与“生命周期已结束”：前者保留事务账本并安全重试，后者才允许硬复位。
- 对库存事务的 release 路径建立 `null capture → RESTORE → CLOSE → next round` 回归，并固定检索 marker：`[AutoToolSwap] release fact capture failed; preserving round for retry`。
