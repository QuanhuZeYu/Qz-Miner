# 自动工具预备态目标未绑定

## 错误现象

按住连锁键但首块尚未成功时，准星从目标 A 切到需要另一工具的目标 B，客户端仍保留 A 的候选与换位结果；短暂扫过空气也缺少明确防抖边界。

## 触发场景

- PREPARING 已为 A 形成或完成普通 SWAP，随后准星切到 B/C。
- FULL 库存扫描期间再次读取 `objectMouseOver`，light 目标与候选目标不一致。
- 准星单 tick 离开方块，若直接按 ABSENT 处理会制造无意义 RESTORE。

## 根本原因

客户端事实快照没有目标身份，`SwapExpectation` 也未绑定候选来源。reducer 只按 10 tick 水位重扫，已有 expectation 时长期只采样受保护槽，因此目标变化无法进入唯一 ledger 的安全恢复链。

## 修复方案

- 新增仅含 `present + blockId + metadata` 的不可变目标身份，在 light 阶段一次采样并传入完整上下文；普通 FULL 禁止二次读取准星。
- reducer 独占 latest/matched、连续 ABSENT 计数和重匹配资格；有效目标立即 latest-wins，ABSENT 连续两个 END tick 才确认。
- expectation 绑定目标。未提交 SWAP 可丢弃；已提交 SWAP/RESTORE 完成后，先清唯一 ledger，再在下一 END tick只为最终有效目标 FULL。

## 预防措施

- 目标驱动的异步事务必须把“输入身份—候选—事务期望”绑定为同一不可变链，禁止中途重新读取运行态目标。
- 回归固定覆盖 A→B→C、A→B→A、SWAP/RESTORE in-flight、同 block/meta 换坐标、metadata 变化、1/2 tick ABSENT，以及 GUI/re-anchor/FROZEN/reset 优先级。
- 协议与库存写权保持正交：客户端只读，服务端 single ledger，任何重匹配不得在旧 ledger 上追加普通 SWAP。
