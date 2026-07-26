# 液体范围交互漏接空气右键语义目标

## 错误现象

四种范围交互只注册并接收 Forge `RIGHT_CLICK_BLOCK`。标准空桶与常见 Item 对液体的原版使用通常走 `RIGHT_CLICK_AIR → tryUseItem`，因此玩家正常对准 vanilla source 时不会启动液体范围交互；客户端普通 `objectMouseOver` 也不会把默认非碰撞液体作为方块命中，预览同样缺失。

已有液体执行器虽然能在虚拟姿态下走精确 `RIGHT_CLICK_AIR` 与 Item 路径，却在执行前用容器注册表和动态流体容器接口预判接收能力。未建模但合法的工业单元或第三方 Item 会在自身右键逻辑运行前被拒绝。

## 触发场景

- 玩家按住连锁键，以标准空桶对准原版静态水/岩浆 source 正常右键；Forge AIR event 的坐标是占位值，不是被瞄准液体。
- 第三方流体 Item 通过自身 `onItemRightClick` 或等价路径处理 source，但不满足 Qz-Miner 的预判接口/注册表模型。
- 客户端液体模式沿用 `Minecraft.objectMouseOver`，准星视觉上对准 source，却没有可供预览启动的 BLOCK hit。

同一输入在部分物品或方块组合中可能先后产生 BLOCK/AIR 两个 Forge event；该现象不改变两者应解析到同一当前语义目标的要求。

## 根本原因

实现把 Forge action 类型误当成产品语义目标：`RIGHT_CLICK_BLOCK` 携带目标坐标，但 `RIGHT_CLICK_AIR` 只描述动作分支，真实目标仍需在 Item mutation 前按当前姿态射线解析。只观察 BLOCK 必然漏掉标准桶路径；反过来读取 AIR 的占位坐标会制造错误 seed。

执行层又把“某些已知容器可模拟接收”错误提升为全部 Item 的准入条件。范围交互的产品边界实际是“把当前手持物对每个计划目标执行正常右键”，物品是否处理应由 Forge event 与 Item 自身决定。

## 修复方案

- 在旧 enum 常量之后追加仓内宽泛 `RIGHT_CLICK`，四种范围交互统一声明该 trigger；planner 只为该 trigger 接收 BLOCK/AIR。
- 新增不含 client-only 类型的共享 `InteractionRayTrace`，复刻 Item 1.7.10 current/prev pose、remote eye offset、调用侧 reach 与 `func_147447_a(..., includeLiquids, false, false)`。
- 非液体 BLOCK 保留 event 坐标；其它 AIR 用不含液体射线；液体 BLOCK/AIR 都用包含液体射线。只有 BLOCK hit 才在原回调内冻结坐标、face/hit offset、block、完整 metadata 与 tile token，再发布既有 `RightClickObserved`。
- 液体客户端预览改用同一包含液体射线，其它模式继续 `objectMouseOver`；执行期精确门也改调共享 helper。
- 删除容器能力 policy。液体执行器只要求当前栈非空且数量为正，保留权限、冻结 seed、同种 live source、虚拟姿态、精确 ray、Forge AIR event、`tryUseItem`、姿态恢复与库存同步。
- 单目标 Item false、DENY、异常或无动作沿既有执行桥跳过并继续；迟到重复观测由既有 ARMED/执行状态门丢弃，不新增事务去重系统。

## 预防措施

- 设计输入链路时分开记录“Forge action”与“业务语义目标”，逐项确认事件字段是否真的携带坐标，以及目标事实在哪个 mutation 前窗口仍可读取。
- 服务端入口、客户端预览和执行期目标化 Item 路径共用一份 side-neutral 射线数学，并以结构测试守 client-only 边界、include-liquids 参数和 reach 来源。
- 宽泛 Item 语义默认让物品自决；除非出现可复现的安全/复制问题，不用类型白名单或能力模拟替代正常 Forge/Item 调用。
- 自动化只证明结构与 JVM 合同。真实 vanilla bucket、GT/IC2 单元、第三方 Item、保护插件、client/dedicated 与 BLOCK/AIR 顺序继续标记 **INCOMPLETE**。
