# 自动工具恢复把动态内容误作跨轮次租约

## 错误现象

自动工具已成功 SWAP 并完成连锁后，正常拾取合并或工具使用改变 count、damage、energy/NBT。客户端在发送 RESTORE 前判定布局不匹配并进入 ORPHANED；服务端保持 `CLOSING` 且 ledger 存在，后续按键无法建立新 round。

同类边界还包括初始主手为空：工具借出后，其原槽可能被掉落物占用。若把空 anchor 解释为“候选槽必须永远为空”，客户端会提前 ABANDON、服务端也会拒绝本可安全完成的双槽交换。

## 触发场景

- 原主手被移到候选槽后发生数量合并或 NBT 更新。
- 活动工具产生耐久、GT 能量 NBT 或其他合法动态变化。
- 工具破损成为空槽，或第三方库存操作把受保护槽替换为不同 role。
- 初始主手为空，借用工具的原槽随后被拾取掉落物占用。

## 根本原因

同一个 full-content fingerprint 被同时用于“本次请求是否仍新鲜”和“跨 round 是否仍属于原角色”。前者需要 exact，后者若也 exact，就会把合法动态变化误判为所有权丢失。恢复失败后又只有要求 ledger 已清的 CLOSE，没有显式放弃语义，形成客户端 ORPHANED 与服务端永久 ledger 锁死。

空 anchor 分支另有一层语义误判：空槽没有需要追回的原资产，candidate 的当前占位物可以安全随原子交换进入主手；要求其继续为空，混淆了“原物角色租约”与“槽位永久为空约束”。

## 修复方案

- 协议升级为 v2，但保持五包 framing、方向与注册数量不变。
- intent 双槽 fingerprint 继续与服务端当前双槽 exact 比较，承担当前请求新鲜度门。
- ledger 跨 round 改用 registry id + stable subtype 的 role 租约；普通 count、damage、energy、NBT 不属于 role。空槽只兼容空槽，活动工具允许同 role 或破损后的空槽。
- role 不兼容时客户端发送显式 `ABANDON(5)`。服务端核验当前身份、序列、状态、ledger 槽位与 canonical control fingerprint 后，零库存访问清 ledger 并进入 FINISHED。
- 原 anchor 为空时，客户端与服务端在保留当前双槽 exact 新鲜度、活动工具 role/empty 和安全上下文门的前提下，允许 candidate 为任意当前真实栈；RESTORE 原子交换双槽，使工具回原槽、占位物进入主手。非空 anchor 的异 role 分支仍拒绝并走 ABANDON。

## 预防措施

- 设计库存事务时分别命名并测试 fresh exact 与 lease role，禁止复用一个比较函数表达两种语义。
- 测试矩阵必须覆盖动态内容变化、stable subtype 变化、空槽占用、sameRole 但 intent 陈旧、ABANDON 身份错配与幂等重放。
- 空槽租约测试必须同时断言交换前后引用集合不变、不合并、不覆盖、不吞物；并保留非空 anchor 异 role 零写拒绝回归。
- 无法安全补偿的事务必须有显式、可审计、零副作用的终止动作；不得借用普通 CLOSE 隐式清账。
