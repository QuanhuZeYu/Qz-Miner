# 异步规划丢失触发窗口种子身份

## 错误现象

正常 `BreakEvent` 触发的 same-block 连锁对含 TileEntity 的方块系统性产出 empty plan。同 block、同 metadata、同类 TileEntity 的邻块在 candidate filter 首门即被拒绝，matcher 与采掘能力门未运行。

同类缺口后来出现在范围交互：`RIGHT_CLICK_BLOCK` 的原事件窗口没有携带 seed，下一 tick 规划才回读 world。若原右键已收获作物、改变 metadata、吸取液体或令方块变为空气，规划拿到的是动作后的状态，目标集合与玩家实际触发对象不一致。

## 触发场景

`BreakEvent` 发布后由下一 tick 主线程 drain 推进 `BlockBreakObserved → PlanStarted`。原版已在当前 tick 移除 origin；事件只携带 block/metadata，规划桥把 seed TileEntity 固定为 null，而候选坐标仍能读到 TileEntity，形成单边身份并被严格拒绝。该问题适用于任何依赖 TileEntity 身份的普通 same-block 方块，不是单一模组特例。

范围交互路径同样经 `RightClickObserved → PlanStarted` 延迟消费；不同点是原右键事件发生在动作生效前，因此该回调就是 block、完整 metadata 与 live TileEntity 的最后存活窗口。把“右键块通常仍在 world”当成保证，会在收获、容器和模组交互上稳定读到后来事实。

## 根本原因

事件链没有逐项识别世界事实的最后存活窗口，却把后续 World 回读当成可恢复事实。block/metadata 的及时传播修复了破坏 origin 变空气，却遗漏了同一时刻存在的 TileEntity 身份；右键路径则连 block/metadata/token 都推迟到动作后读取。两者根因都是把不可恢复的触发时事实误当成可延迟采样状态。

## 修复方案

- 引入状态为 `ABSENT/PRESENT/UNRESOLVED` 的不可变纯值 `TileIdentityToken`；token 不含 TileEntity、World、Block、NBT、Class、坐标或反射成员。
- 在 `BreakEvent` 服务端主线程读取 seed TileEntity 并立即转 token，沿 round 事件、状态机进态广播、seed snapshot 与搜索上下文传播。
- 在服务端 `RIGHT_CLICK_BLOCK` 原回调中、publish 前冻结 `Block`、完整非负 int metadata 与 live TileEntity token；状态机原样传播，规划桥优先使用冻结三元组。读取异常固定为 `UNRESOLVED`，不得下一 tick 用后来 world 值覆盖或猜测。
- 左键兼容 resolver 仍只在其既有主线程路径捕获；本次不借右键修复改写 GT SPECIAL 触发合同。
- 普通 same-block worker 只消费 seed token；候选 TileEntity 从当前只读 World 取得后立即转 token。block/metadata 仍先精确比较。
- 已识别 adapter 读取失败固定为 `UNRESOLVED` 且不降级；未知模组按 runtime type name 保持既有 class 语义。seed `UNRESOLVED` 在 worker 登记前以固定原因 `shadow-seed-tile-identity-unresolved` 取消。
- 保留 GT 线缆特殊模式与客户端预览的既有专用兼容字段；本次不声称完成全 worker 世界快照化。

## 预防措施

- 对异步消费的世界事实逐项检查“事实最后存活窗口”，先写缺失传播的失败测试，再接事件链；同在主线程不等于同一事实时刻。
- 为 token 纯值约束、完整身份矩阵、adapter 失败不降级、未知同/异类、无 TileEntity、破坏/右键事件原样传播、完整 int metadata、legacy `UNRESOLVED` 与 worker 启动前取消建立纯 JVM/结构回归。
- 生产 matcher 禁止模组名特判；诊断只输出有界状态/策略/类型与固定原因，不输出 identity key、NBT、owner 或对象。
