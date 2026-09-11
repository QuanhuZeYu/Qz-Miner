# ERROR-20260817-gtnh-284-baseline-upstream-pom-defect.md

**日期**：2026-08-17
**组件**：branch-ci GTNH 基线矩阵（`gradle/gtnh-baselines.json`）
**状态**：已处置（2.8.4 基线移除，仅保留 2.9.0-beta-2；文档与错误记录同步）
**现状（2026-09-11）**：多基线矩阵机制已退役，基线真源改为 `dependencies.gradle` 单值声明；2.8.4 仍不在兼容承诺内。详见文末「现状标注」。

## 现象

Miner branch-ci 的 GTNH 2.8.4 baseline job 在 Setup the workspace 阶段依赖解析失败：

    Could not find com.github.GTNewHorizons:CodeChickenLib:1.3.0

依赖链：GT5-Unofficial 5.09.51.482 → ThaumicTinkerer → ThaumicBoots → Thaumic_Exploration
1.4.2-GTNH → CodeChickenLib:1.3.0。

## 根因

上游 POM 缺陷：`Thaumic_Exploration:1.4.2-GTNH` 声明了错误 group 的
`com.github.GTNewHorizons:CodeChickenLib:1.3.0`；GTNH 的 CodeChickenLib 实际发布
group 为 `codechicken`，`com/github/GTNewHorizons/CodeChickenLib` 在 GTNH nexus
public/releases 与 mavenCentral 均 404（已 curl 实证）。2.9.0-beta-2 基线的 GT5
5.09.54.20 不触发该链，全绿。

## 处置

- 2.8.4 基线从 `gradle/gtnh-baselines.json` 移除，CI 矩阵仅保留 2.9.0-beta-2；
  branch-ci.yml 与 release-tags.yml 的基线数量校验由「恰两个」放宽为「至少一个」。
- README「5.3 联机版本边界」注明：5.3.0 仅承诺 2.9.0-beta-2 基线；2.8.4 不再承诺。

## 教训

- 多基线 CI 矩阵的每个基线都依赖上游 maven 坐标长期可用；上游 POM 缺陷会以
  「ModuleVersionNotFound」形式在干净 runner 上暴露，本机因缓存旧依赖图而不复现。
- 基线矩阵声明的是兼容承诺：移除基线前必须取得用户确认并同步文档，不静默降级。

## 现状标注（2026-09-11，多基线机制退役）

- 本轮起 GTNH 基线与兼容承诺改为单基线声明：唯一真源是 `dependencies.gradle` 的
  `elytraModpackVersion { setGtnhVersion("2.9.0-beta-3") }`（对齐 Qz-UILib）。
  `gradle/gtnh-baselines.json`、`scripts/verify-gtnh-baselines.ps1` 与 `build.gradle.kts` 的
  `verifyGtnhBaseline` 任务已删除；`branch-ci.yml` 不再有基线矩阵与 `prepare-baselines`，
  `release-tags.yml` 不再解析基线清单，二者也不再传 `-Pelytra.manifest.version` / `-Pqz.gtnh.*`。
- 本文的处置结论（2.8.4 依赖图无法解析、不再承诺兼容）仍然有效，只是不再由 CI 矩阵表达。
  2.9.0-beta-2 / beta-3 的同 jar 兼容改以「源码对 GTNH 侧组件零静态链接 + 双基线 `compileJava`/
  `compileTestJava` 实证」为据；真机运行态未验证。
- 本文「教训」仍然成立：基线声明的是兼容承诺；变更基线声明或退役机制前必须取得用户确认并同步文档。
  机制退役的取舍与代价见 `ERROR-20260911-gtnh-single-baseline-matrix-retirement.md`。
