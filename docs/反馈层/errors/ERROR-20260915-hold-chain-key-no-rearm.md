# 按住连锁键无法持续连锁（每按一次只能连锁一次）

## 错误现象

- 按住连锁键不松手，左键连锁执行完 A 后，再瞄准 B／C／D 左键都不再连锁；松手重按可恢复一轮。
- 创造模式下稳定复现（每按一次键只能连锁一次）；生存模式多数情况下正常，只有在自动换位能力不成立时才复现。

## 触发场景

- 创造模式（{@code EntityPlayer.capabilities.isCreativeMode == true}）。
- 关闭自动换工具（{@code client.autoToolSwapEnabled=false}）。
- 当前子模式触发器不是 {@code BREAK_BLOCK}（INTERACT_*/GT 线缆替换/扫雷）。
- 自动换位 round 进入 ORPHANED / 被服务端拒绝 / fresh key 发送失败。

## 根本原因（2026-09-15 实机探针定位）

1. 客户端只在**物理边沿**发送按键电平：{@code KeyListener.onClientTick} 只有
   {@code isPressed != wasPressed} 才走 {@code updateChainKeyState} → {@code PacketKeyState(KEY_CHAIN,true)}。
   全仓发送点只有 {@code KeyListener:204/:215} 两处，按住期间没有重述。
2. 服务端武装是**纯边沿驱动**：{@code ChainStateMachine} T1 只在 {@code slot.phase == IDLE} 时接受
   {@code ChainKeyPressed(true)}；其余态静默丢弃。
3. 每次连锁收尾都回 {@code IDLE}（T6/T8/T9/T10），这是有意设计。
4. 于是"按住不放"的服务端停在 IDLE，后续 {@code BlockBreakObserved}/{@code LeftClickObserved} 全部在 T4
   前置条件被丢；而 planner 侧门控读的是另一套权威（服务端按键电平 {@code isChainKeyPressed()}，按住期间恒 true），
   所以事件**确实产生了**，只是被状态机拒绝——玩家端零反馈。
5. 唯一能把"键仍按住"重新告知服务端的通道，是自动换位 round 的 fresh-key 副作用
   （{@code AutoToolSwapClientReducer} 的 {@code rearmAfterClose → BEGIN_ROUND(freshKey=true) → FRESH_KEY effect}
   → {@code KeyListener.sendFreshChainKeyPressedToServer}）。该通道带四道 fail-closed 守卫
   （{@code configuredEnabled} / {@code breakCapable} / {@code creative} / {@code chainActive}），
   失败即进入粘性 {@code WAIT_RELEASE}／{@code ORPHANED}（只有松键可退）。创造模式恰好命中 {@code creative} 守卫，
   于是"按键武装"这个与自动换位毫无关系的功能被一并废掉。

### 实机证据（同一会话：生存 → 创造对照）

| 探针通道 | 生存模式 | 创造模式 |
|---|---|---|
| {@code C-GUARD-FAIL} | 无 | {@code creative=true}（3 次）→ {@code C-REDUCER IDLE->WAIT_RELEASE} |
| {@code C-REARM-SET} / {@code C-FRESHKEY} | 各 6 次 | 0 次 |
| {@code S-KEYPUB serverRoundId} | 1..7（关联成功） | 0（无 round 可关联） |
| {@code S-PHASE} | 每轮 {@code FINISHING->IDLE} 后紧跟 {@code IDLE->ARMED on=ChainKeyPressed round=N+1} | 仅第一次真实边沿有 {@code IDLE->ARMED}，此后无 |
| {@code S-DROP} | 无（观测被消费） | 连续 6 次 {@code BlockBreakObserved phase=IDLE wouldBe=PLANNING} |

即：生存模式靠 fresh-key 通道"接住"了每轮收尾，掩盖了框架缺陷；创造模式四道守卫之一失效，缺陷直接暴露。

## 修复方案

- 输入语义改为**电平授权**：{@code PlayerPhaseSlot} 新增 {@code keyDown} 电平（唯一写在
  {@code onChainKeyPressed}），该 handler 无条件先写电平再按"电平 × 当前态"决定转移；同义重复
  （ARMED 收 true / 活跃态收 true）幂等静默，不再记为越界。
- 新增转移 **T11 {@code IDLE→ARMED}**：收尾路径（T6/T8+T9/T10）先照旧回到 {@code IDLE}（保留看门狗清条目、
  自动换位 {@code closesRound(IDLE)}、客户端相位投影等既有消费者语义），随后若 {@code keyDown} 仍为 true
  则追加一次再武装（{@code settleToIdle(slot, event, allowRearm)}；LOGOUT 的 {@code removeSlot=true} 不重武装）。
- T3（{@code ModeSwitched}）按住期间不再解除武装——"按住连锁键 + 滚轮切模式"是文档化手势
  （README），原实现会让玩家切完模式后再也连锁不了。
- 客户端零改动；自动换位的 fresh-key 保留（仍负责新 round 语义），其重复 {@code pressed=true} 在 ARMED 态幂等。

依赖的顺序契约：{@code PacketKeyState} 先 publish {@code ChainKeyPressed(false)} 再 publish
{@code LifecycleCleanup(user-abort)}，{@code ChainEventBus.drain()} 严格 FIFO —— 松键即停仍成立
（电平先落 false，收尾不再武装）。

## 预防措施

- 回归：{@code ChainStateMachineTest} 新增 {@code heldKeyRearmsOnExecutionCleanup}、
  {@code heldKeyRearmsOnWatchdog}、{@code heldKeyRearmsOnPlanCancelled}、
  {@code heldKeyRearmsAndSecondObservationStartsPlanning}（本轮缺陷的直接回归）、
  {@code releasedKeySettlesIdleAfterCleanup}、{@code logoutCleanupDoesNotRearm}、
  {@code repeatedKeyPressedWhileArmedIsIdempotent}、{@code modeSwitchedWhileHeldKeepsArmed}。
- 框架原则：{@code chain/package-info.java} 新增第 8 条「输入语义分层：持续意图用电平，动作意图用边沿」。
- 结构约定：**不得**再把"按键武装"寄生于任何业务协议（自动换位 round 等）的副作用上；武装只认按键电平。
- 实机验证口径：创造模式下按住连锁键连续挖 3 个以上方块应每块都连锁；生存模式与关闭自动换工具时行为一致。
