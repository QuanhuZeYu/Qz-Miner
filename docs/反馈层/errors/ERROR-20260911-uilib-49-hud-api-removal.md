# ERROR-20260911-uilib-49-hud-api-removal

**日期**：2026-09-11
**组件**：Qz-UILib 4.9.0 的 `club.heiqi.uilib.ui.hud.api`；Miner 侧 `client/QzMinerHudSnapshotProvider`、`ClientProxy` 与对应测试
**状态**：已迁移（编译与过滤测试绿；完整 build 与双基线待 verifier 验证）

## 现象

把本地 devjar 从 `libs/qz_uilib-4.8.0-dev.jar` 换成 4.9.0 制品后，Miner 在 beta-3 基线下
`compileJava` 直接失败，共 12 条「找不到符号」，全部集中在 HUD API：

- `src/main/java/club/heiqi/qz_miner/client/QzMinerHudSnapshotProvider.java:15-19`：
  `HudLine` / `HudSnapshot` / `HudSnapshotProvider` / `HudSpan` / `HudTone` 五处静态 import 失败，
  并连带同文件 22/58/145/150/154 行的实现签名无法解析；
- `src/main/java/club/heiqi/qz_miner/ClientProxy.java:33`：`CompactHud` 静态 import 失败；
- 测试侧（**未实测**，compileJava 先失败）：`QzMinerHudSnapshotProviderTest.java:20-23` 与
  `HudArchitectureBoundaryTest.java:46` 同样引用旧契约。

同一次编译中，非 HUD 的 UILib 引用（`ClientHudService`、`HudAnchor`、`HudRegistration`、
`HudSpec`、scene/screen 与 image API）均解析通过，说明只有 HUD 子系统断裂。

## 证据（jar 级，本轮独立复核）

脚本 `temp/miner-ci-check/check_hud_api.py` 直接比对两个 devjar 的类存在性：

| 类 | 4.8.0-dev.jar | 4.9.0-dev.jar |
| --- | --- | --- |
| `CompactHud` / `HudLine` / `HudSnapshot` / `HudSnapshotProvider` / `HudSpan` / `HudTone` / `TextHud` | present | ABSENT |
| `HudWindowFactory` / `HudLayoutService` / `HudToolbarService` | ABSENT | present |
| `HudAnchor` / `HudRegistration` | present | present |

`ui/hud/api` 包内类条目数：4.8.0 = 17，4.9.0 = 26。12 条编译错误逐条清单见工作站探针报告
`temp/miner-beta3-probe/REPORT.md` C-2 节（归属 Qz-UILib 4.9.0，与 GTNH 基线无关）。

## 根因

UILib 4.9 对 HUD 做了破坏性重构：删除 `CompactHud` + `HudSnapshotProvider` 拉取式契约，改为
`HudWindowFactory` / `HudSpec` / `HudLayoutService` 等声明式 + Signal 契约。Qz-Miner 是 Miner
唯一被静态链接的第三方依赖，编译期直接绑定类名，因此删除面等于编译断裂面。

## 处置（最终形态）

- 删除旧 `QzMinerHudSnapshotProvider` 与 `QzMinerHudSnapshotProviderTest`，改为三段式：

  | 组件 | 职责 |
  | --- | --- |
  | `client/QzMinerHudModel` | 业务状态 → 不可变语义模型（稳定行/片段 id、文本、`Tone`）；不引用 UILib HUD API |
  | `client/QzMinerHudWindow implements HudWindowFactory` | `build(SceneRuntime)` 用 scene 代码构建一次内容树；`Signal<QzMinerHudModel>` + `SceneRuntime.forEach` keyed 列表刷新；`SceneChromeTokens.HUD_TEXT_*` 着色 |
  | `client/QzMinerHudTicker` | `ClientTickEvent.END` → `refresh()` 写 signal（值不变不写） |

- 注册仍是 `ClientProxy.init` 单点：`ClientHudService.getInstance().register(HudSpec.builder("qz_miner:chain-status").anchor(HudAnchor.TOP_LEFT).build(), factory)`；
  断线只释放宿主 session 窗口，注册句柄不重注册、不 close。
- 功能等价点：显示门（连锁键按下，或阶段处于 PLANNING/RUNNING/FINISHING）与旧快照一致；
  空模型 → keyed 列表无行 → 内容根零尺寸 → 宿主整窗隐藏（对齐旧 EMPTY 快照）；
  色调语义 `Tone.{MUTED,INFO,SUCCESS,WARNING}` 映射 `SceneChromeTokens.HUD_TEXT_*`，文本不含样式编码；
  锚点仍为左上角，安全区/缩放/裁剪仍归 UILib 宿主。
- `HudAnchor` / `HudRegistration` 在 4.9 仍存在；非 HUD 引用点（scene/screen/image API）逐个核查签名后仅做最小修正。

## 验证（2026-09-11；编译/测试由 api-migrate 实测，计数由本轮独立读取 XML 复核）

- `compileJava` / `compileTestJava` 与 `client.*` 过滤测试 exit 0。
- `build/test-results/test`：26 套件 / 159 tests / 0 failures / 0 errors（同一次运行，12:01）。
  其中 HUD 三套件 `HudArchitectureBoundaryTest` 5 + `QzMinerHudModelTest` 7 + `QzMinerHudWindowTest` 5 = 17 tests，0 失败；
  `client.*` 25 套件 158 tests，另含非 client 的 `MyModMetadataTest` 1 test。
- 守卫仍失败关闭：`HudArchitectureBoundaryTest` 禁止生产源码出现旧 HUD 符号
  （`CompactHud`/`HudSnapshotProvider`/…）与原版渲染符号（`RenderGameOverlayEvent`/`FontRenderer`/`drawString`/`hudX`/`hudY`），
  并断言注册单点、唯一 `HudWindowFactory`、不 close、ticker 单点。
- **未验证**：完整 `build`（checkstyle + 全量 JUnit + assemble）与双基线复测归 verifier，尚未出结论；真机运行态未验证。

## 教训

- 升级 UILib 前先做 jar 级类存在性差分（`ui.hud.api` 这类"稳定清单"之外的包最容易整包重构），
  比等 CI 编译失败更快定位删除面。
- `compileJava` 断裂会连带阻塞 `compileTestJava`；迁移时必须同时清点测试源码对旧契约的静态引用，
  不能只按 main 的错误清单施工。
