# 网络版本兼容

## 结论

- Qz-Miner `5.2.0` 使用 Forge 唯一 `@NetworkCheckHandler` 实施严格 minor-family 握手；当前兼容族固定为 `major=5, minor=2`。双方 patch、prerelease 与 build metadata 不参与兼容判定。
- stable、prerelease、branch/dirty dev 只要版本语法完整合法且 core 属于 5.2，就在 CLIENT/SERVER 两个方向互通；`5.0.x`、`5.1.x`、`5.10.x`、其他 minor 与畸形版本均 fail-closed。
- 不提供 5.1 capability negotiation、wire fallback 或 AutoToolSwap takeover compatibility surface。5.2 两端必须共同遵守本文件的完整合同。
- 远端版本表不含精确 key `qz_miner` 时两个方向均返回 true；存在该 key 时，本地与远端版本都必须是合法 5.2 family。missing-mod 放行只影响 Forge mod-list checker，不创建替身、channel 或无 Mod 运行保证。

## 完整版本语法

接受的 ASCII grammar 为：

```text
major.minor.patch[-prerelease][+build]
```

- core 恰有三段十进制非负整数，无前导零且不超过 signed `int`。
- prerelease/build 由点分隔的非空 identifier 组成，字符仅 ASCII 字母、数字与 `-`。
- prerelease 的纯数字 identifier 无前导零；build 的纯数字 identifier 可有前导零。
- 输入不 trim，不允许前后垃圾、Unicode、空 identifier、额外 core 段或重复 `+`。

例如 `5.2.0`、`5.2.3-rc.1`、`5.2.0-ci+<SHA>` 与合法 branch/dirty qualifier 可互通；`5.1.1`、`5.10.0`、`05.2.0`、`5.2.0-01`、`5.2.0+` 均拒绝。

## 5.2.0 冻结基线

`NetworkMain` 使用显式常量固定以下 discriminator、消息与接收 Side：

| ID | Packet | Side |
|---:|---|---|
| 0 | `PacketKeyState` | SERVER |
| 1 | `PacketChainModeSwitch` | SERVER |
| 2 | `PacketChainSubModeSwitch` | SERVER |
| 3 | `PacketChainConfigRequest` | SERVER |
| 4 | `PacketObjectGroupConfigRequest` | SERVER |
| 5 | `PacketLootGamesMinesweeperPreviewRequest` | SERVER |
| 6 | `PacketLootGamesMinesweeperPreviewResponse` | CLIENT |
| 7 | `PacketChainPhaseSnapshot` | CLIENT |
| 8 | `PacketChainConfigSync` | CLIENT |
| 9 | `PacketObjectGroupConfigSync` | CLIENT |
| 10 | `PacketAutoToolSwapRoundStart` | SERVER |
| 11 | `PacketAutoToolSwapIntent` | SERVER |
| 12 | `PacketAutoToolSwapRoundResult` | CLIENT |
| 13 | `PacketAutoToolSwapActionResult` | CLIENT |
| 14 | `PacketAutoToolSwapRoundPhase` | CLIENT |
| 15 | `PacketCuboidSelectionRequest` | SERVER |
| 16 | `PacketCuboidSelectionSync` | CLIENT |

同一基线还冻结：

- 自动工具 v4 五帧 `12/96/44/56/36` bytes、字段顺序及动作/状态/结果 code；旧 60-byte takeover frame、独立 request ID 与客户端 fallback 已删除。
- cuboid request/sync 固定为 `20/56` bytes；客户端只发布可排序的服务端 ACK。
- chain config legacy v0 与 extended v2、object group v2、现有固定与可变 framing。
- `ChainMode 0..3`、`ChainSubMode 0..14`、`ChainPhase 0..4` ordinal；`AREA_CUBOID_CLEAR=14`。
- `TunnelDirectionSource` code、对象组七个稳定 mode bit与完整 23-path schema 的顺序/type/default。

`NetworkDiscriminatorRegressionTest`、`QzMiner52WireContractTest`、既有分项协议测试与 `QzMinerConfigSchemaTest` 是可执行锚。5.2 family 内不得重排、复用、删除或改变上述 wire/schema 语义；任何不兼容变化必须升级新 minor，而不是借 dev/prerelease qualifier 绕过。

## 框选权威边界

- `PacketCuboidSelectionRequest` 只提交 point index 与坐标。请求按 endpoint + point 进入 bounded latest-wins lane；服务端主线程重验 endpoint identity、当前 AREA/框选子模式、连锁键未按下以及当前服务端射线精确命中，拒绝客户端自报但未命中的坐标。容量/关闭拒绝也返回当前 immutable 快照 ACK，不静默吞掉已取消的鼠标动作。
- 每位玩家的服务端选择状态持有两点、revision 与 accepted bounds。选区必须同维度，inclusive volume 不超过该玩家当前 accepted `chainMaxBlocks`；失败操作不部分提交。
- `PacketCuboidSelectionSync` 总是返回完整状态与 revision。客户端只渲染 ACK，使用 `[min,max+1]` 的 6 面/12 边 AABB；迟到 revision 不得覆盖新状态。
- planning 在服务端主线程冻结 immutable bounds 到 request/session。触发方块可在选区外；执行中重选不改写当前 round，只影响下一轮。

## 自动工具边界

- 普通 `CHAIN/AREA` 的候选、库存 mutation、restore 与 publication 由服务端本地 physical ledger 独占，不发送逐目标 takeover request，也不等待客户端网络 round-trip。
- 客户端仅发送 `FREEZE/CLOSE` control intent 并观察 result/phase；不扫描库存、不做 target rematch、不持有可写 ledger。
- 5.1 的 takeover packet、coordinator、配置开关与 mixed-minor fallback 已删除。此删除正是升级到 5.2 的不兼容边界，不为旧端保留 dormant decoder 或 source shell。

## 版本注入与证据边界

- 5.2 branch CI 由 runner 声明 `VERSION=5.2.0-ci+${{ github.sha }}`，并检查 generated `Tags.VERSION` 与主 JAR `Tags.class` 的 exact 值；两个 matrix leg 对同一 SHA 使用同一版本。
- tag workflow 继续使用 tag 名注入 `VERSION`。本地未显式注入时固定使用合法的 `5.2.0-dev`，只作
  5.2 family 联调包而不是具体 patch artifact 证据；不通过设置环境变量、Maven Local、`flatDir`
  或 URL 旁路伪造发布制品可消费性。
- GTNHGradle 的 Git version module 已关闭；`build.gradle.kts` 是唯一版本入口，将 runner-owned `VERSION`
  或本地默认值同时写入 `project.version` 与 `modVersion`，避免 JAR 名、资源和 generated `Tags.VERSION` 分裂。
- parser/handler/ID/wire/schema 测试、本地 Gradle、branch CI、tag、artifact、clean consumer、Forge status query 与真实 client/dedicated 运行态分别记录，不能互相替代。
- 真实 5.2 mixed-patch、missing-mod、鼠标取消、AABB/GL、框选执行与配置命令运行态当前均为 **INCOMPLETE**。自动化通过不能升级这些证据等级。
