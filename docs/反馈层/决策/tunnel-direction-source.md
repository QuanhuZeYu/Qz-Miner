# 决策：隧道方向使用每玩家 accepted 来源

## 结论

- `AREA_TUNNEL` 增加每玩家 YAML 偏好 `client.tunnelDirectionSource`，稳定 id 为 `look_direction` 与 `hit_face`，默认 `look_direction` 保持旧行为。
- `LOOK_DIRECTION` 取玩家视线绝对值最大的轴并冻结为 Minecraft 六面编号；分量平局保持既有 Y、Z、X 优先级。
- `HIT_FACE` 取左键命中方块的外法线 opposite，即从被点击表面朝方块内部。左键入口只在服务端主线程记录维度、坐标和 face 纯值，不持有 Player、World 或 Event；随后同维度、同坐标的破坏最多消费一次。
- pending 缺失、非法或坐标/维度失配时立即失效，并回退该次破坏时冻结的 look face。松键、主模式/子模式变化、运行态清理和玩家生命周期清理也会清 pending；非隧道路径不消费它。
- 服务端每玩家状态以最近一次合法 C2S 的 radius/maxBlocks/source 为 accepted 原子组，并在写入后立即发送 S2C ACK。客户端保存配置后不乐观改变预览，只在连接 identity gate 内发布 ACK 的 accepted source；连接初始化与清理回落 `LOOK_DIRECTION`。
- 客户端预览与服务端规划使用同一 concrete face 语义。同一 world、同一 origin 的 face 变化也属于预览 identity 变化，必须递增 generation 并使旧 worker 协作终止。

## Wire 与四象限降级

新帧保留旧字段前缀，只接受精确长度；未知版本、未知方向码、截断或尾随帧整包拒绝。

| 客户端 | 服务端 | C2S / S2C | 方向结果 |
|---|---|---|---|
| 新 | 新 | 16 / 20 字节 | 服务端接受玩家选择并 ACK，支持 `LOOK_DIRECTION` / `HIT_FACE` |
| 旧 | 新 | 8 / 20 字节 | 新服务端把旧 C2S 固定解释为 `LOOK_DIRECTION`；S2C 旧前缀保持可读 |
| 新 | 旧 | 16 / 12 字节 | 旧端忽略兼容尾部；新客户端把旧 S2C 固定解释为 `LOOK_DIRECTION` |
| 旧 | 旧 | 8 / 12 字节 | 保持既有 `LOOK_DIRECTION` |

自动化覆盖 legacy/extended framing 与非法长度；四象限真实混连仍为 **INCOMPLETE**，不得用单元测试替代运行态。

## 不变量影响

- **I1**：命中面与具体方向只在服务端主线程冻结为纯值；worker 只读冻结 face，不写世界或状态。
- **I4**：C2S Handler 只捕获 raw，accepted 写入在服务端主线程；S2C 经客户端 dispatcher 与连接 identity gate 后原子 publication。
- **I7**：连接及玩家生命周期清理会清 accepted/pending，旧连接 ACK 不得跨生命周期回写。
- **I2/I3/I5/I6/I8/I9/I10**：取消、遍历、掉落、反射、时运、屏障与五态转移表均未改变。

## 运行态验收

- 六个视线轴、六个命中面与预览/实际隧道同向。
- pending 失配回退、同 origin 换 face 的预览 generation 隔离。
- 新新、旧新、新旧、旧旧四象限；混连必须降级 `LOOK_DIRECTION`。
- client/dedicated 与真实方块破坏矩阵当前均为 **INCOMPLETE**，待用户实机和日志回流。
