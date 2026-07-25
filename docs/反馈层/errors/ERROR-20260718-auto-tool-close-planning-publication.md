# 自动工具 CLOSE 等待门与规划完成 publication 竞态

## 错误现象

- 真实日志中 `DECLINE seq26 ACCEPTED` 后松键把 round 推到 `CLOSING`，但 `CLOSE seq27` 因 pending takeover 仍非空被拒；随后旧请求身份失配，round 长期停在 `round1/CLOSING`，新规划持续取消。
- 同一运行中曾捕获 `ExecutionFinished` 早于状态机消费 `PlanCompleted`；空队列执行桥可能在完成事件尚未入队时就观察到 `planningComplete=true`。
- GT 5.09.54.20 的线缆 profile 报缺 `IGregTechTileEntity#issueBlockUpdate()` 并整体降级，虽然两项支持基线的该方法实际声明于 `IRedstoneTileEntity`。

## 触发场景

- 接替请求已进入 WAITING/DECLINED/STOP/APPLIED，但松键、恢复或放弃动作使用当前合法 action sequence 收口同一 round。
- worker 完成影子遍历后，在 `ChainExecutionContext` 固化本地完成状态与 `ChainEventBus.publish(PlanCompleted)` 之间发生主线程观察或 publication 异常。
- 初始化 GT 线缆反射 profile 时，从 `IGregTechTileEntity` 精确查找仅由红石接口拥有的通知方法。

## 根本原因

- `AutoToolSwapRoundService` 把“pending 非空则拒绝非 TAKEOVER”放在 sequence 校验和动作分派之前，使执行等待门取得了高于 round 终裁的优先级；key release 只把 WAITING 转 STOP，没有退休 pending。
- `tryCompletePlanningAndPublish` 先写 confirmed count、COMPLETED 与 volatile `planningComplete`，之后才调用 publication，倒置了跨线程可见性的线性化顺序；publication 抛错后还会遗留不可撤销的本地完成态。
- capability profile 没有把方法语义与声明 owner 一并核验，把红石/邻居通知误当成线缆客户端与网络刷新必需能力。

## 当时修复方案（v3）

- endpoint/round/幂等与 sequence 全部通过后，只有 CLOSE/RESTORE/ABANDON 可退休 pending：WAITING 先输出一次 STOP 诊断，DECLINED/STOP/APPLIED 直接移除，再执行既有 CLOSE/ledger 恢复/放弃语义。其它非 TAKEOVER 动作仍被等待门阻断；迟到 TAKEOVER 在 sequence/身份门拒绝且零库存副作用。
- `PlanCompleted` publication 成功返回后才固化 confirmed count 与 COMPLETED，并最后写 `planningComplete=true`。规划桥捕获 `RuntimeException`/`LinkageError`，保持 context ACTIVE 后通过既有单次 worker 取消 publication 发布 `PlanCancelled(reason=plan-completion-publication-failed)`；生产 completion Runnable 只做 bus publish，成功后再写诊断。
- 从 GT profile 删除 `issueBlockUpdate` 的字段、解析、调用、copy、missing 与 all-present 条件；继续要求 `issueTextureUpdate`、`issueTileUpdate` 和 `causeCableUpdate`。

## 当时预防措施（v3）

- 异步资源等待门只能延迟继续执行，不能覆盖生命周期终裁；测试矩阵必须覆盖 WAITING/DECLINED/STOP/APPLIED × CLOSE/RESTORE/ABANDON，以及旧 sequence/endpoint/round 的零副作用拒绝。
- 跨线程完成标志必须最后写；测试用 latch 卡住 publication，直接断言 publication 返回前 `isPlanningComplete/isCompleted=false`，并注入运行时异常与链接错误验证仅一次取消且无 COMPLETED 残留。
- 可选模组 required profile 逐项记录 owner 与业务语义；升级基线时同时核对“成员存在于哪里”和“替换真正依赖什么”，不得为方便扩大 I6 反射面。
- 自动化通过不能替代自动工具、规划事件顺序与两代 GT 线缆真实运行态；hotfix 实机状态继续记为 **INCOMPLETE**。

## v4 当前边界

- TAKEOVER/DECLINE 已从普通 `actionSequence` 分离为独立 `takeoverRequestId`。CLOSE/RESTORE/ABANDON 仍可在普通 sequence 精确通过后退休尚未 committed 的等待门，不再被 request-local deadline、发送或目标漂移放大阻断。
- mutation 或待发送 ActionResult 已建立 exact publication pending 后，普通 CLOSE/RESTORE/ABANDON、deadline、release 与 IDLE 都不得抢占该提交点；它们必须等待同一结果可靠发布，只有断线、重生、切维度、服务停止等硬 lifecycle 清理可直接销毁记录。该规则避免“库存已经改写、却被普通收口遗忘 result/ledger”的新竞态。
- 等待 publication 的目标保持 `peek` 且 Coordinator 返回 WAIT，不发布伪 `ExecutionAdvanced`；完整库存同步或 ActionResult sender 失败时只 exact retry，不重放 mutation。详细 recovery 合同见 `ERROR-20260725-auto-tool-request-sync-recovery.md`。
