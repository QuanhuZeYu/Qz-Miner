# ERROR-20260911-elytra-manifest-single-source

**日期**：2026-09-11
**组件**：Elytra conventions v1.1.2 的基线属性 `elytra.manifest.version` 与 `-P` 覆盖；
`gradle.properties` 的 `elytra.manifest.no-cache`
**状态**：已处置（真源单值化）；离线构建路径风险待观察

## 现象（实测）

基线真源迁到 `dependencies.gradle` 后，`-Pelytra.manifest.version` 不再生效：

- `gradlew.bat -Pelytra.manifest.version=2.9.0-beta-2 -Pqz.gtnh.expectedGregTechVersion=5.09.54.20
  --rerun-tasks compileJava compileTestJava` 解析到的仍是 beta-3 的
  `com.github.GTNewHorizons:GT5-Unofficial:5.09.54.133`（`beta2-deps-r2.log`，
  exit 0 但版本与传参不符）；
- 同一传参配 `verifyGtnhBaseline` 直接失败：
  `Resolved com.github.GTNewHorizons:GT5-Unofficial version 5.09.54.133, expected 5.09.54.20`；
- 不带任何 `-P` 时走 `dependencies.gradle` 真源，同样解析 `5.09.54.133`。

## 根因

`dependencies.gradle` 的 `elytraModpackVersion { setGtnhVersion("2.9.0-beta-3") }` 在项目配置阶段
写入基线值，实测其值胜出；同时 `gradle.properties` 已删除 `elytra.manifest.version`，
不再有"属性默认值 + `-P` 覆盖"的第二来源。此前矩阵能按基线分别构建，正是依赖这条 `-P` 覆盖通道。

## 处置

- CI 与本地诊断不再传 `-Pelytra.manifest.version` / `-Pqz.gtnh.*`；基线真源唯一。
- 需要双基线编译实证时，按工作站任务笔记的人工流程：临时改 `setGtnhVersion` → 编译 →
  用 `try/finally` 按原始字节恢复并校验 sha256（禁止用 `-P` 假装切换基线）。

## 离线构建风险（本仓尚未复现，如实标注）

本轮同时删除了 `gradle.properties` 的 `elytra.manifest.no-cache=true`（单值化）。Elytra conventions
v1.1.2 的 ManifestUtils 在默认 `no-cache` 下会直接向 GitHub 拉取 manifest；本机存在 Fake-IP 干扰时
表现为 `Failed to load the manifest from Github`。Miner 与 UILib 均有过实测记录：

- 本仓 `ERROR-20260814-uilib-local-devjar-build.md`（gtnhconvention 拉取 manifest TLS 超时）；
- UILib `docs/反馈层/errors/ERROR-elytra-offline-manifest-cache.md`（同一插件版本，离线构建需用
  init script 调 `setManifestNoCache(true)` 复用 `build/elytra_conventions` 缓存）。

离线构建若命中，优先用上述 init script 或按 `ERROR-20260814` 注入代理；不要把属性再写回仓库，
否则会重新引入第二真源。

## 教训

- 基线真源只允许一个；单值化之后任何 `-P` 或属性覆盖都会失效，或变成第二真源。
- 每次基线声明变更后，双基线证据必须重新生成，旧的 `-P` 流程证据不可复用。
