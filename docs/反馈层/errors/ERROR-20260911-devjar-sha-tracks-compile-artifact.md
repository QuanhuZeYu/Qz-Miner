# ERROR-20260911：主 dev jar 的 sha256 随编译期 UILib 制品变化（构建证据口径）

## 现象

`.changelogs/5.3.1.md` 记录的主 jar `qz_miner-5.3.0-dev.jar` sha256 `407e663f…`，
在重新交接 UILib 4.9.1 制品（缩放内聚化 `fe809f1f`，件 sha256 `57BAB4C2…`）后重建变为
`b38d2fc0…`，一度被怀疑为构建不稳定 / GC 或时间戳抖动。

## 取证（2026-09-11）

- **控制实验**：verifier 在同一 HEAD 下换回旧 UILib 件（`FFE47AC6…` / 3776681 bytes）重建，
  可**精确复现** `407e663f…`；换新件重建稳定得到 `b38d2fc0…`。排除时间戳、缓存、并发抖动。
- **源码零变化**：`d72b3d6` 只改 `dependencies.gradle` 注释与 `libs/qz_uilib-4.9.1-dev.jar`
  （`git diff --stat 43a374e d72b3d6 -- ':!libs'` 仅 1 文件 3 行注释），两次构建的源码完全一致。
- **两个 sha 不受影响**：同一次替换中 `qz_miner-5.3.0-dev-dev.jar`（`bd3e722d…`）与
  `qz_miner-5.3.0-dev-sources.jar`（`d8d9fb7d…`）sha256 不变，本次对照重建核对一致。
- **差异面定位**：同一构建内 `-dev.jar` 与 `-dev-dev.jar` 的 719 个条目集合一致、全部非 class 条目
  （`mcmod.info` / `MANIFEST.MF` / refmap / lang / LICENSE）逐字节一致，665 条差异**全为 class 字节**；
  即主 jar 的差异面落在 reobf 后的 class 字节，而非元数据。

## 结论

- `qz_miner-5.3.0-dev.jar`（主/reobf jar）的字节是「Miner 源码 + 编译期 UILib 制品」的联合指纹；
  `-dev-dev.jar` / `-sources.jar` 对 UILib 制品不敏感。
- 记录构建证据时，主 jar sha **必须与交接 jar 的 sha（及 HEAD 提交）成对保存**；换 UILib jar 后主 jar
  sha 变化是**预期行为**，不能据此判定构建不稳定，也不能只凭主 jar sha 比对历史记录。

## 附带发现（交接纪律）

重新交接的 4.9.1 件与前一版 4.9.1 件**版本号相同、内容不同**：新增 1 个类
（`club/heiqi/uilib/ui/hud/api/HudScaleRegistry.class`）、13 个类变更（`SceneHudHost` /
`ChatHudEditPreviews` / `ChatInputSurface` / `HudScaleState` / `HudToolbarService` 等）。
`QzUiLibArtifactContractTest` 只校验 `mcmod.info` 版本满足声明区间，**识别不了这种同版本内容漂移**；
必须靠 `dependencies.gradle` 注释里的 sha256 与 changelog 记录人工核对。

## 处置

- changelog 主 jar sha 回填为当前实测值，并注明与交接件 sha、HEAD 的对应关系；
- 本条记录进入 `docs/反馈层/errors/`：后续换 UILib 制品时，按此口径重跑
  `gradlew --rerun-tasks build`（不 clean）并成对回填三个产物 sha。
