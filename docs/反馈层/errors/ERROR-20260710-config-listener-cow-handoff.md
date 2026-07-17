# 配置 listener COW 交接丢捕获

## 错误现象

- UILib event bus 使用 COW 快照分发时，旧 listener 回调可能在 replacement 完成之后仍被调度。
- 若 replacement 仅 seed 当时的 `currentCommittedSnapshot`，而 Authority 已在旧回调窗口内保存成功但尚未 capture，则已提交配置无人捕获、不进入 mailbox。

## 触发场景

- BATCH_SAVE 成功写盘后，listener 替换（manager 热切换或测试/重订阅）与 COW 快照回调交错。
- 旧 callback 因 identity gate 返回，新 listener 又只 seed 旧 current。

## 根本原因

- 替换路径只 seed `currentCommittedSnapshot`，未在 subscribe 切换完成后重新从 Authority capture。
- 当前锁图未证实死锁；问题是交接语义缺口，不是锁环。

## 修复方案

- replacement 在 `SUBSCRIPTION_LOCK` 内完成 unsubscribe/subscribe/active 切换后，调用 `ConfigBootstrap.captureCommittedSnapshot(manager)` 重新捕获 Authority，再锁外 dispatch。
- 补确定性测试：无 active listener 时 save 使 current 滞后，replacement 仍能 recapture 到保存后的值。
- 边界说明：UILib event bus 精确 COW 屏障无法从 Miner 测试钩住，测试用最接近的可控场景覆盖。

## 预防措施

- listener 替换交接必须 recapture Authority，勿仅 seed 可能未更新的 current。
- 不要在文档声称未证实的死锁；交接问题用语义测试固定。
- 守配置发布 freshness 与 I4 分侧 mailbox 契约。
