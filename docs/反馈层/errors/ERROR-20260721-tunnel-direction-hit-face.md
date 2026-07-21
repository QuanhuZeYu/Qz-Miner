# ERROR：隧道方向不能由 BreakEvent 占位面或实时视线代替

## 错误现象

`AREA_TUNNEL` 只能按玩家视线主轴开掘；玩家从方块侧面点击时，预览与实际隧道无法稳定按点击表面朝方块内部延伸。若直接复用破坏事件中的占位 face，还会把固定值误当成真实命中面。

## 触发场景

- 玩家选择按命中面确定隧道方向，并以六个不同方块面触发左键破坏。
- 左键事件与随后破坏事件坐标或维度失配，或前一个命中 pending 未及时清理。
- 同一 origin 下准星命中面改变，但预览仅以 origin 作为任务 identity。
- 新旧客户端/服务端混连，配置帧没有显式 legacy 降级。

## 根本原因

Forge 1.7.10 的 `BlockEvent.BreakEvent` 不暴露 sideHit；既有链路只能填占位值或重新读取 look。命中面事实实际存在于更早的 `PlayerInteractEvent.LEFT_CLICK_BLOCK`。若不建立窄的一次性桥接，就无法证明该 face 属于随后被破坏的同一方块；若预览不把 concrete face 纳入 identity，旧 worker 还能把旧方向结果写回同 origin。

## 修复方案

- 在服务端主线程左键入口仅为普通 `AREA_TUNNEL` 记录维度、坐标和 face 纯值，latest-wins 保存。
- 破坏入口仅在 accepted source 为 `HIT_FACE` 时按同维度、同坐标消费一次，并使用外法线 opposite；任何缺失、非法或失配都清 pending 并回退同次冻结 look。
- 松键、模式/子模式变化、运行态及玩家生命周期清理统一使 pending 失效；非隧道路径保持隔离。
- 配置 source 由服务端 accepted 后 ACK，客户端预览只读 ACK 值；concrete face 纳入预览 generation identity。
- 配置 wire 保留兼容前缀：C2S 8/16 字节、S2C 12/20 字节，legacy 固定降级 `LOOK_DIRECTION`，非法 framing 整包拒绝。

## 预防措施

- 对“前置交互事实 → 后续不完整事件”建立坐标、维度、生命周期和一次性消费四重约束，不传递 World/Player/Event 对象。
- 测试覆盖六轴、六面、opposite、latest-wins、失配消费、模式隔离、accepted 原子发布、ACK 前不乐观预览和同 origin 换 face。
- 协议测试同时固定 legacy/extended 长度、旧前缀和非法长度，运行态继续验证新新/旧新/新旧/旧旧四象限。
