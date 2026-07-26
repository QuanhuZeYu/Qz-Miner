# 网络版本兼容

## 结论

- Qz-Miner 从 `5.1.0` 起以 Forge 唯一 `@NetworkCheckHandler` 实施 minor-family 握手；当前兼容族固定为
  `major=5, minor=1`。双方 patch、prerelease 与 build metadata 不参与兼容判定。
- stable、prerelease、branch/dirty dev 只要版本语法完整合法且 core 属于 5.1，就在 CLIENT/SERVER
  两个方向互通；`5.0.x`、`5.10.x`、其他 minor 与畸形版本均 fail-closed。
- 远端版本表不含精确 key `qz_miner` 时两个方向均返回 true；存在该 key 时，本地与远端版本都必须是
  合法 5.1 family。null map/modId/Side、存在但 null/空/畸形的值一律 false。
- missing-mod 放行仅影响 Forge mod-list checker，不创建替身、channel 或 capability，也不承诺后续
  `SimpleNetworkWrapper` 与业务主动发送在无 Mod 对端上安全。

## 完整版本语法

接受的 ASCII grammar 为：

```text
major.minor.patch[-prerelease][+build]
```

- core 恰有三段十进制非负整数，无前导零且不超过 signed `int`。
- prerelease/build 由点分隔的非空 identifier 组成，字符仅 ASCII 字母、数字与 `-`。
- prerelease 的纯数字 identifier 无前导零；build 的纯数字 identifier 可有前导零。
- 输入不 trim，不允许前后垃圾、Unicode、空 identifier、额外 core 段或重复 `+`。

例如 `5.1.0`、`5.1.3-rc.1`、`5.1.0-ci+<SHA>` 与合法 branch/dirty qualifier 可互通；
`5.0.24`、`5.10.0`、`05.1.0`、`5.1.0-01`、`5.1.0+` 均拒绝。

## 5.1.0 冻结基线

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
| 15 | `PacketAutoToolSwapTakeoverRequest` | CLIENT |

同一基线还冻结：

- 自动工具 v4 六帧 `12/96/44/56/36/60` 字节及其动作/状态/结果 code；
- chain config legacy v0 与 extended v2、object group v2、现有固定与可变 framing；
- `ChainMode 0..3`、`ChainSubMode 0..13`、`ChainPhase 0..4` ordinal；
- `TunnelDirectionSource` code、对象组七个稳定 mode bit 与完整 24-path schema 的顺序/type/default。

`NetworkDiscriminatorRegressionTest`、`QzMiner51WireContractTest`、既有分项协议测试与
`QzMinerConfigSchemaTest` 是可执行锚。5.1 family 内不得重排、复用、删除或改变上述 wire/schema
语义；任何不兼容变化必须升级到新的 minor family，而不是借 dev/prerelease qualifier 绕过。

## 版本注入与发布前边界

- 首个 5.1 tag 前，branch CI 的每个 baseline job 由 runner 声明 `VERSION=5.1.0-ci+${{ github.sha }}`，
  并在 build 后检查 generated `Tags.VERSION` 与主 JAR `Tags.class` 的 exact 值；两个 matrix leg 对同一
  SHA 使用同一版本。
- tag workflow 继续使用 tag 名注入 `VERSION`，一般 release/prerelease 渠道策略不由本决策改变。
- 本地未显式注入时 GTNHGradle 仍可从最近 `5.0.24` tag 派生 5.0 core；这种本地 build 不是 5.1
  artifact 证据，不通过设置环境变量、Maven Local、`flatDir` 或 URL 旁路伪造。

## 证据分层与升级条件

- parser/handler/ID/wire/schema 测试、本地 Gradle、branch CI、tag、artifact、clean consumer、Forge
  status query 与真实 client/dedicated 运行态分别记录，不能互相替代。
- 真实 5.1 stable/pre/dev mixed runtime、missing-mod client/dedicated 与 status query 当前均为
  **INCOMPLETE**。用户决定它们不阻断首个候选提交，但每个后续 patch 都必须独立、显式保留该状态，
  不能从前一版本继承通过或风险接受。
- 若必须改变冻结的 packet、framing、协议、ordinal、code、mask 或 schema，或实证表明 5.1 family
  无法安全互通，则建立新任务评估并升级 minor；不在 patch/qualifier 中暗改合同。
