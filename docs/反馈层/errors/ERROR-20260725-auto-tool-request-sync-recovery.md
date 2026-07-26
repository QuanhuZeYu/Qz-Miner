# 自动工具请求身份与写后 publication 恢复

## 错误现象

- 单个 TAKEOVER 的请求发送、deadline、目标漂移或写前库存拒绝会被放大为会话级 STOP，当前目标失败即可丢弃后续已规划目标。
- 库存交换已正常返回后，若原版库存同步或 Qz-Miner ActionResult 发送失败，旧实现只能把 round 标为 orphan/sync-failed；重放原 intent 又可能重复交换，无法同时保证“最终可见”和“mutation 仅一次”。
- 普通动作 sequence 同时承担接替请求 ownership 时，旧请求的迟到包、同号改 payload 和下一目标请求难以在零库存副作用边界隔离。
- Coordinator 在 service 拒绝退休 committed publication pending 时若仍删除本地 issued ownership，会让目标从 WAIT 提前滑落为跳过或新请求，造成库存已经写入而 gate 结算丢失。

## 触发场景

- request sender 抛出 `RuntimeException`/`LinkageError`，或目标在服务端等待期间切换。
- `sendContainerToPlayer(...)` 连续失败，随后恢复；或库存同步成功而 ActionResult sender 抛错，客户端只能重发同一 intent。
- A 请求已经发送，B/C 目标随后到达；A/B 的结果在 C 已取得 ownership 后迟到。
- TAKEOVER mutation 已提交但 gate 仍 WAITING，此时同时发生 deadline、release、IDLE、目标/执行身份漂移或 watchdog/lifecycle 清理。

## 根本原因

- request identity、普通动作进度和目标消费缺少分层：一个 sequence 既像动作提交号又像执行门租约，局部失败只能借 STOP 逃生。
- mutation、原版库存 publication、ActionResult publication 与 sequence/gate confirm 被压在一次调用中；“写入成功”不等于“客户端已看到完整布局并收到结算”，也不能在任一发送失败后安全重放写入。
- `detectAndSendChanges()` 依赖容器差异缓存；一次失败尝试可能已消费 dirty/delta，后续调用不保证重发完整布局。
- 客户端只有一次性发送/短超时与宽泛 reset，不能表达 immutable exact retry、较长准备/关闭 deadline、已提交 takeover 的不可抢占布局验证，以及新旧 request 的独立 ownership。

## 修复方案

- 协议原子升级到 v4，仅把 TAKEOVER/DECLINE 的既有 long 关联字段解释为 `takeoverRequestId`；六包、动作码、字段顺序和固定 framing 不变。每 round 的 request ID 在 `prepareTakeover` 成功建立请求时立即消耗，永不回滚；普通 SWAP/RESTORE/FREEZE/ABANDON/CLOSE sequence 完全独立。
- 服务端分别缓存最近一个已确认 ordinary exact 结果和 takeover exact 结果。发行 `r+1` 后，旧 `r` exact cache 也不可回放；旧号、未来号及同号改 payload 都在库存读取、写入、同步和诊断快照前拒绝。
- 每个 round 至多安装一个 `PendingPublication`。mutation 正常返回是唯一 commit；后续 exact 重入只调用完整个人库存 publication 并返回同一 ActionResult。每次库存重发都重新 `markDirty()`，再调用 `sendContainerToPlayer(player.inventoryContainer)` 发送完整 window 0，绝不再次交换双槽或轮转三槽。
- ActionResult sender 正常返回后的 `confirmIntentResultPublication` 是唯一确认点：ordinary 在此推进 sequence，takeover 在此提交 APPLIED/DECLINED/SKIP_TARGET gate 终态并缓存 exact 结果。同步失败只返回 `SYNC_FAILED` 并保留 pending；sender 抛错同样不 confirm，客户端下一 tick 重发同一 intent。
- Coordinator 对发送失败、deadline、目标/执行身份漂移精确调用 `skipTakeoverGate`；只有 service 真实退休后才删除 issued ownership 并返回 `SKIP_TARGET`。若 exact publication 已 committed，退休会被拒绝，Coordinator 必须保留 ownership 并返回 WAIT；下一目标只能在旧门安全消费后重新读取 anchor/库存并取得更大 request ID。
- 客户端普通静默请求按固定 20 tick cadence 重发；BEGIN/SWAP/RESTORE/CLOSE/ABANDON 使用 120 tick 硬 deadline，`SYNC_FAILED` 在后续 tick exact retry。新 takeover 可替换尚未 committed 的旧 ownership；旧 mutation committed 或布局验证中只排队最新请求，A→B→C 最终保留 C，A/B 的迟到结果不得清当前 ordinary in-flight 或污染 C。
- 断线、重生、切维度、服务停止与真实 watchdog 是硬 lifecycle：直接销毁 round、ledger、pending publication、issued/queued/in-flight 与布局观察，不把旧事务带入新 lifecycle。普通 release/IDLE/deadline/漂移不能抢占 committed publication pending。

## 预防措施

- 所有可重试库存事务都必须显式标出四个线性化点：request burn、mutation commit、inventory publication、result confirm；不得用一个 boolean/sequence 同时代表四者。
- 写后重试测试必须覆盖双槽与三槽两条路径，连续至少两次 sync failure、sync 恢复后 sender failure、再次 exact retry，并分别断言 mutation=1、完整 sync 次数、result 内容和最终 ledger。
- request cache 测试固定覆盖：已确认 exact 可幂等、同号改 payload 拒绝、发行 `r+1` 后旧 `r` exact 拒绝、ordinary cache 与 takeover cache 互不污染、所有拒绝均为零库存/同步/诊断副作用。
- gate 测试固定覆盖 committed pending × deadline/目标漂移/执行身份漂移/release/IDLE/watchdog/lifecycle：普通门保持 WAIT/ownership，真实 watchdog 或 lifecycle 才允许硬清理；WAIT 不 poll 且不得生成伪推进事件。
- 客户端 reducer 测试固定覆盖 0/19/20/39/40 tick cadence、119/120 tick deadline、`SYNC_FAILED` 次 tick exact retry、普通 in-flight 与 TAKEOVER 解耦，以及 A→B→C 乱序结果。
- 静态/JVM 门禁不能替代 client/dedicated 的真实连续接替、网络发送故障与 HUD/预览库存可见性验证；运行态继续标记 **INCOMPLETE**。
