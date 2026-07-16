# 自动工具持续按键无法开启下一轮

## 错误现象

用户持续按住连锁键时，孤立目标的首轮能自然执行 `IDLE→RESTORE→CLOSE`，但下一方块不能开启新的自动工具 round；松键后重新按下才恢复。

## 触发场景

- 物理连锁键从首轮开始始终保持按下。
- 首轮规划因空计划或自然完成回到连锁 `IDLE`。
- 自动工具旧 round 正常还原并关闭后，玩家继续破坏下一方块。

## 根本原因

`KeyListener` 正确地只处理物理按键边沿。旧 round 自然关闭时 controller 把持续按键解释为等待释放，后续既没有第二次真实 down 边沿，也没有新 nonce 的 RoundStart 与 fresh key=true。服务端因而没有可激活的 PENDING round，状态机在 IDLE 收到下一次方块观测时按 I10 越界丢弃。

## 修复方案

- 保留每轮自然 `IDLE→RESTORE→CLOSE`，不复用或复活旧 round。
- controller 私有区分 `NATURAL_REARM` 与优先级更高的 `RELEASE_GATED`。仅旧 CLOSE 精确 FINISHED 后留下一个 deferred 资格，不在 S2C callback 内发包。
- adapter 在下一 `ClientTick` 同时确认 controller 资格、客户端协议 IDLE、逻辑键与物理键都为 down 后，创建新 nonce 并成功提交 RoundStart；随后 `KeyListener` 复用配置同步与通用 `PacketKeyState(KEY_CHAIN, true)` 路径激活新 round。
- 松键/快速重按、禁用、生命周期复位、拒绝、orphan、发送失败或非 FINISHED CLOSE 全部 fail-closed，并清除资格。

## 预防措施

- 回归同时覆盖 callback 内零 C2S、下一 tick 一次性 `RoundStart2→fresh key=true`、nonce/round ID 不复用，以及物理 false、release gate、禁用、reset、reject/orphan 与发送失败。
- 服务端测试锁定 FINISHED round 的裸 key=true 不可复活，只有新 nonce 建立 PENDING round 后 fresh key=true 才能 OPEN。
- 状态机测试锁定 fresh key 先合法 `IDLE→ARMED`，之后带新 round 的观测才能 `ARMED→PLANNING`；不得新增转移表捷径。
