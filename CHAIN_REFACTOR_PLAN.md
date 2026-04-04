# 连锁系统重构规划

## 目标

重构现有连锁功能，降低客户端、服务端、网络、预览、执行、搜索之间的耦合度。

重构后的系统需要满足：

- 状态来源单一，避免客户端与服务端状态漂移。
- 搜索、执行、预览、网络各自独立。
- 长耗时搜索可以在并行 Tick 框架中跨 Tick 分片执行。
- 世界修改只能由主线程执行，避免并行访问导致崩溃。
- 玩家离线、切维度、重生、单人退出时可以正确清理连锁状态。

## 当前已具备的基础设施

当前项目已经有以下可复用基础层：

- 玩家生命周期管理：`core/PlayerManager`
- 模组事件总线：`event/*`
- 网络通信入口：`network/NetworkMain`
- 客户端输入与 HUD：`client/KeyListener`、`client/HudOverlay`
- 并行 Tick 分片框架：`parallel/*`
- 生命周期边界 Mixin：`mixins/*`

后续连锁系统应建立在这些基础设施之上，不再复用旧版高耦合结构。

## 总体分层

建议新增 `chain` 模块，并按职责拆分为以下层级：

### 1. `chain.state`

职责：

- 管理每个玩家的连锁运行状态
- 管理客户端预览状态
- 管理当前模式、配置快照、任务句柄
- 响应玩家生命周期事件并做清理

建议类：

- `ChainPlayerState`
- `ChainClientState`
- `ChainSession`
- `ChainStateService`
- `ChainSessionRepository`

### 2. `chain.mode`

职责：

- 定义连锁模式
- 管理模式切换
- 建立模式到搜索策略、执行策略的映射

建议类：

- `ChainMode`
- `ChainModeState`
- `ChainModeRegistry`

说明：

模式状态对象只保存状态，不负责直接创建具体搜索器。

### 3. `chain.input`

职责：

- 接收客户端按键输入
- 接收服务端交互/挖掘触发
- 将输入转换为领域命令

建议类：

- `ChainInputService`
- `ChainToggleCommand`
- `ChainModeSwitchCommand`
- `ChainActionRequest`

### 4. `chain.planner`

职责：

- 负责搜索和规划“哪些方块/目标会被连锁处理”
- 负责大范围候选目标的增量计算
- 与并行 Tick 框架集成
- 支持多种遍历方式与多种匹配规则的自由组合

建议类：

- `ChainPlanner`
- `ChainPlan`
- `ChainTarget`
- `ChainTraversalTask`
- `ChainRuleEvaluator`
- `ChainLimitPolicy`
- `ChainPlanningStrategy`
- `ChainTraverser`
- `ChainMatcher`
- `ChainPlanningContext`

说明：

- 这一层只做计算，不做世界写入。
- 可以访问世界数据，但不能直接破坏方块或修改玩家。
- 未来规划器的变体，不应继续堆在单个 `ChainPlanner` 中，而应拆为“遍历器 + 匹配器”组合。
- 规划策略的变化，本质上分为两类：如何找到候选目标、候选目标是否匹配。
- 典型组合示例：
  - 方块洪泛遍历 + 同类方块匹配
  - 范围盒扫描 + 矿物匹配
  - 半径扫描 + 实体类型匹配
  - 视线方向扫描 + 可交互对象匹配

### 5. `chain.executor`

职责：

- 在主线程执行真正的世界修改
- 消费 `ChainPlan`
- 执行挖掘、交互、掉落处理、耐久检查等逻辑
- 支持多种目标消费方式，而不局限于挖方块

建议类：

- `ChainExecutor`
- `ChainExecutionTask`
- `ChainExecutionQueue`
- `ChainDropCollector`
- `ChainActionExecutor`
- `ChainExecuteResult`

说明：

- 执行层必须与规划层解耦。
- 并行线程永远不直接执行 `tryHarvestBlock` 等世界修改。
- 执行器本体应退化为调度器，真正的业务动作由具体执行策略负责。
- 后续执行变体建议包括：
  - `BlockHarvestActionExecutor`
  - `BlockInteractActionExecutor`
  - `EntityInteractActionExecutor`
  - `EntityAttackActionExecutor`

### 6. `chain.client`

职责：

- 客户端预览区域计算与展示
- HUD 状态展示
- 渲染缓存与预览生命周期管理

建议类：

- `ChainPreviewController`
- `ChainPreviewState`
- `ChainPreviewPlannerBridge`
- `ChainOverlayPresenter`

说明：

- 客户端预览与服务端执行可以共用规划规则，但不要共用控制器。
- 预览是独立模块，不直接操纵服务端执行状态。

### 7. `chain.sync`

职责：

- 封装连锁模块的网络协议
- 同步客户端输入与服务端权威状态

建议类：

- `PacketChainToggle`
- `PacketChainModeSwitch`
- `PacketChainStateSync`
- `ChainNetworkBridge`

说明：

- 网络层只负责传输，不负责业务编排。

### 8. `chain.integration`

职责：

- 将连锁模块挂接到现有基础设施中
- 订阅玩家事件、输入事件、网络事件
- 初始化连锁模块

建议类：

- `ChainModule`
- `ChainEventBridge`
- `ChainPlayerLifecycleBridge`

## 核心设计原则

### 1. 单一事实来源

以下状态不能再出现多份副本乱飞：

- 是否按住连锁键
- 当前连锁模式
- 当前连锁配置
- 当前是否正在执行
- 当前是否正在预览

建议：

- 客户端只维护客户端显示与输入态。
- 服务端维护真正的执行态。
- 需要展示时由服务端同步权威状态到客户端。

### 2. 规划与执行分离

规划层：

- 做搜索
- 做筛选
- 做路径/集合构建

执行层：

- 做方块破坏
- 做物品交互
- 做掉落处理
- 做最终效果应用

不能让规划线程直接修改世界。

### 2.1 规划器按策略组合解耦

不要继续把所有搜索、规则判断、候选生成都塞进一个 `ChainPlanner`。

建议将规划层拆为：

- `ChainPlanningStrategy`：一次规划任务的总控策略
- `ChainTraverser`：负责产生候选目标
- `ChainMatcher`：负责判断候选目标是否匹配

推荐接口方向：

```java
public interface ChainPlanningStrategy {
    void start(ChainSession session, ChainTrigger trigger);
    boolean step(ChainSession session, int budget);
}
```

```java
public interface ChainTraverser {
    boolean step(ChainPlanningContext context, int budget, ChainCandidateConsumer consumer);
}
```

```java
public interface ChainMatcher {
    boolean matches(ChainPlanningContext context, ChainCandidate candidate);
}
```

这样以后新增规划器变体时，只需要替换遍历方式、匹配方式或总控策略，而不需要重写整个主流程。

### 3. 预览与执行分离

预览需要的只是“候选区域”与“可视化结果”。

执行需要的是“最终计划”和“主线程动作”。

两者可以共享规则，但不应共享生命周期控制器。

### 3.1 执行器按动作策略解耦

执行层未来不一定是挖掘，所以不要继续围绕 `tryHarvestBlock` 建模整个系统。

建议抽象统一动作执行接口：

```java
public interface ChainActionExecutor {
    boolean canExecute(ChainSession session, ChainCandidate candidate);
    ChainExecuteResult execute(ChainSession session, ChainCandidate candidate);
}
```

这样 `ChainExecutor` 只保留：

- 会话是否继续运行的判断
- 待执行队列消费
- 节流与 Tick 调度
- 调用具体动作执行器

而不再直接承载挖掘、交互、攻击等具体业务。

### 3.2 目标模型从方块坐标提升为统一候选对象

当前 `ChainTarget` 只适合表示方块坐标，这会限制未来实体类目标扩展。

建议逐步升级为统一目标抽象：

```java
public interface ChainCandidate {
    ChainCandidateType getType();
}
```

首批实现建议：

- `BlockCandidate`
- `EntityCandidate`

这样规划层和执行层都只依赖统一候选对象，不需要在主流程中写大量“如果是方块/如果是实体”的分支。

在完全迁移前，`ChainTarget` 可以作为 `BlockCandidate` 的过渡形态存在，但最终不应继续作为唯一目标模型。

### 3.3 玩家状态与会话状态分离

`ChainPlayerState` 更适合保存玩家级长期状态，不适合继续承载一次连锁任务的全部运行细节。

建议职责划分：

- `ChainPlayerState`：玩家级状态
  - 是否按住连锁键
  - 当前模式
  - 当前展示状态
  - 当前是否存在活动会话

- `ChainSession`：单次连锁任务状态
  - 触发源
  - 规划状态
  - 执行状态
  - 遍历容器
  - 待执行容器
  - 停止原因

这样后续无论规划器还是执行器如何扩展，运行时复杂度都被限制在 session 内部，不会污染全局玩家状态对象。

### 4. Mixin 只做边界补钩子

Mixin 只负责：

- 生命周期边界补足
- Tick 边界补足
- 断连边界补足

不要把连锁的业务决策写进 Mixin。

### 5. 并行框架只做增量计算

并行 Tick 框架适合：

- 搜索
- 预览区域计算
- 大集合筛选
- 候选计划构建

不适合：

- 世界写入
- 掉落生成
- 方块真实破坏
- 实体状态直接修改

## 推荐的数据流

### 服务端执行链

1. 客户端输入上传到服务端
2. `chain.state` 更新玩家连锁状态
3. 服务端事件触发连锁请求
4. `chain.state` 创建 `ChainSession`
5. `chain.mode` 根据当前模式选择规划策略与执行策略
6. `chain.planner` 开始或推进搜索任务
7. 规划层逐步产出 `ChainCandidate`
8. `chain.executor` 在主线程按 Tick 消费候选目标
9. 触发执行结果事件
10. 同步必要状态给客户端

### 客户端预览链

1. 本地输入进入客户端状态层
2. `chain.client` 判断是否需要预览
3. 客户端并行规划任务在 `CLIENT_PRE/CLIENT_POST` 阶段推进
4. 产出预览候选区域
5. 渲染层读取预览结果并展示

## 与旧架构的替换关系

旧结构中的高耦合职责，建议按如下方式替换：

- 旧 `Manager`
  - 拆为：`ChainStateService` + `ChainEventBridge` + `ChainDropCollector`

- 旧 `BaseOperator`
  - 拆为：`ChainExecutor` + `ChainExecutionTask`

- 旧 `BaseChainViewer`
  - 拆为：`ChainPreviewController` + `ChainOverlayPresenter`

- 旧 `MinerModeState.createPositionFounder(...)`
  - 改为：`ChainModeRegistry` 提供模式到策略映射

- 旧 `Founder`
  - 改为：规划层中的增量任务对象，不再直接继承线程

## 模式到策略的映射原则

`ChainModeRegistry` 未来不能只维护模式列表和循环切换能力，还应负责注册“模式 -> 策略组合”的映射。

建议新增模式定义对象：

```java
public final class ChainModeDefinition {
    private final ChainMode mode;
    private final ChainPlanningStrategy planningStrategy;
    private final ChainActionExecutor actionExecutor;
}
```

注册关系建议为：

- `CHAIN` -> 同类目标规划 + 挖掘执行
- `AREA` -> 范围规划 + 挖掘执行
- `INTERACT` -> 范围或视线规划 + 交互执行
- `SPECIAL` -> 自定义规划 + 自定义执行

注意：

- `ChainMode` 本身只做标识，不直接承载业务逻辑。
- `ChainModeRegistry` 负责查找模式定义。
- 具体业务能力由策略对象承担。

## 分阶段落地计划

### 第一阶段：状态与模式抽离

目标：先把边界搭起来，不急着恢复完整功能。

实现内容：

- 新建 `chain.state`
- 新建 `chain.mode`
- 定义 `ChainPlayerState`
- 定义 `ChainClientState`
- 定义 `ChainMode`、`ChainModeRegistry`

完成标准：

- 连锁键状态、模式状态、配置状态有统一存放位置
- 不再让模式状态对象负责直接 new 搜索器

### 第二阶段：客户端预览最小闭环

目标：优先恢复客户端预览链路。

实现内容：

- 新建 `chain.client`
- 新建 `chain.planner`
- 做一个最小的预览搜索任务
- 接入 `ParallelTickExecutor.CLIENT_PRE` 或 `CLIENT_POST`
- 将结果显示到渲染层

完成标准：

- 按住连锁键时可以增量计算预览区域
- 松开键时可以稳定取消任务并清理状态

### 第三阶段：服务端执行最小闭环

目标：恢复最基础的服务端连锁执行。

实现内容：

- 新建 `chain.executor`
- 新建服务端 `ChainPlanner`
- 规划层只生成目标列表
- 执行层在主线程按 Tick 消费目标列表

完成标准：

- 能针对一种最简单模式执行连锁挖掘
- 无并行线程直接改世界

### 第四阶段：网络同步完善

目标：建立客户端与服务端的完整状态闭环。

实现内容：

- 新建 `chain.sync`
- 明确客户端输入包与服务端状态同步包
- HUD 与客户端预览改为读取统一状态

完成标准：

- 客户端显示状态与服务端执行状态一致
- 断线、单人退出、切维度后状态能正确清理

### 第五阶段：模式扩展与规则迁移

目标：逐步迁移旧版复杂模式。

建议顺序：

1. 普通连锁同类方块
2. 范围连锁
3. 作物模式
4. 原木/矿脉模式
5. 交互模式
6. 特殊模式与兼容逻辑

### 第六阶段：策略层彻底解耦

目标：把当前可运行实现从“单实现可用”提升为“多规划器/多执行器可扩展架构”。

实现内容：

- 引入 `ChainSession`
- 引入 `ChainPlanningStrategy`
- 引入 `ChainTraverser`、`ChainMatcher`
- 引入 `ChainActionExecutor`
- 将 `ChainTarget` 逐步升级为统一 `ChainCandidate`
- 让 `ChainModeRegistry` 从模式枚举表升级为模式定义注册表

完成标准：

- 新增一种规划器变体时，不需要改核心主流程
- 新增一种执行器变体时，不需要改核心主流程
- 主流程只依赖抽象接口，不依赖“挖方块”这一种具体能力

当前进度：

- 已引入 `ChainSession`
- 已引入 `ChainPlanningStrategy`
- 已引入 `ChainActionExecutor`
- 已将 `ChainModeRegistry` 升级为模式定义注册表
- 已将 `BlockFloodFillPlanningStrategy` 初步拆分为 `ChainTraverser + ChainBlockMatcher + SeedResolver + ContextFactory`
- `ChainTarget` 已具备方块/实体双容器与存储模式字段
- 当前仍只承接 `CHAIN` 模式的实际业务实现

### 第七阶段：统一候选目标与多目标执行链

目标：把当前“兼容旧方块坐标接口”的实现，推进到真正统一的候选目标模型，为实体规划与交互执行做准备。

实现内容：

- 引入 `ChainCandidate` 或等价统一目标抽象
- 让 `ChainTarget` 从“主要表示单个方块坐标”过渡为“统一目标载体”
- 为方块目标与实体目标分别补充访问器与校验逻辑
- 让执行器消费逻辑显式处理 `BLOCK / ENTITY / ALL`
- 评估并整理客户端预览链对新目标模型的适配

完成标准：

- 主流程不再默认假设目标一定是单个方块坐标
- 为实体类目标接入规划器与执行器时，不需要再回头推翻当前主流程

### 第八阶段：模式扩展与注册装配完善

目标：在当前注册骨架可用的前提下，补齐模式装配流程，准备后续新增模式。

实现内容：

- 继续清理 `ChainModeBootstrap` 的装配职责
- 让模式定义注册支持更明确的初始化入口和校验
- 为 `AREA`、`INTERACT` 等模式预留明确的空实现或占位定义
- 视需要补充注册冲突、缺失定义的日志与保护

完成标准：

- 模式注册过程清晰、集中、可审查
- 后续新增模式时只需新增策略并在装配入口注册

## 当前建议的首批类

建议先实现以下最小类集：

- `chain/state/ChainPlayerState.java`
- `chain/state/ChainClientState.java`
- `chain/state/ChainStateService.java`
- `chain/mode/ChainMode.java`
- `chain/mode/ChainModeRegistry.java`
- `chain/planner/ChainTarget.java`
- `chain/planner/ChainPlan.java`
- `chain/client/ChainPreviewController.java`
- `chain/executor/ChainExecutor.java`
- `chain/integration/ChainModule.java`

## 当前阶段的最小重构顺序建议

在现有基础功能已经闭环可用的前提下，后续解耦建议按以下顺序推进，避免一次性大改：

1. 先引入 `ChainSession`
2. 把当前运行时队列、规划状态、执行状态迁入 `ChainSession`
3. 抽出 `ChainPlanningStrategy`
4. 将当前实现封装为默认策略，例如 `BlockFloodFillPlanningStrategy`
5. 抽出 `ChainActionExecutor`
6. 将当前挖掘逻辑封装为 `BlockHarvestActionExecutor`
7. 最后再把 `ChainTarget` 升级为统一 `ChainCandidate`

这样可以保证每一步都可编译、可测试、可回退。

## 实现顺序建议

后续开发严格按以下顺序推进：

1. 先建立状态层
2. 再建立模式层
3. 再接客户端预览规划
4. 再接服务端执行
5. 最后补完整网络同步和复杂模式

不要一开始就尝试把旧版所有 Founder 和 Operator 一次性迁移回来。

## 暂不做的事情

以下内容暂缓，避免过早复杂化：

- 一次性迁移所有旧模式
- 立即恢复所有旧兼容逻辑
- 让客户端预览和服务端执行共用控制器
- 让规划线程直接改世界
- 在 Mixin 中承载业务逻辑
- 一开始就引入过重的泛型体系
- 让 `ChainMode` 枚举直接承担业务逻辑

## 结论

后续连锁系统实现，应以 `chain` 模块为中心，依赖现有基础设施完成分层重建。

实现原则总结：

- 状态统一
- 规划与执行分离
- 预览与执行分离
- 并行只做计算
- 世界修改只在主线程
- Mixin 只做边界

后续所有连锁功能实现，均以本规划为准。
