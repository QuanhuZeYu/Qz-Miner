# ERROR-20260911-gtnh-single-baseline-matrix-retirement

**日期**：2026-09-11
**组件**：branch-ci / release-tags 的 GTNH 基线矩阵；`build.gradle.kts` 的 `verifyGtnhBaseline`
**状态**：已处置（机制退役：单基线真源 + 人工双基线编译实证）

## 背景与取舍

原机制：`gradle/gtnh-baselines.json` 登记基线清单 → `branch-ci.yml` 的 `prepare-baselines`
生成矩阵 → 每个基线作业用 `-Pelytra.manifest.version` / `-Pqz.gtnh.expectedGregTechVersion`
分别构建 → `release-tags.yml` 只跑默认基线。本轮改为 Qz-UILib 形态：`dependencies.gradle`
单值 `setGtnhVersion("2.9.0-beta-3")` + 源码级运行期兼容承诺；json / ps1 /
`verifyGtnhBaseline` 任务与矩阵全部退役。

退役依据（实测，非推断）：

- Miner 对 GTNH 侧组件零静态链接：main+test 中 `gregtech.*` / `com.gtnewhorizons.*` /
  `gtnhlib.*` / `com.mitchej123.*` / `ganymedes01.*` / `net.covers1624.*` / `me.eigenraven.*`
  静态 import 数均为 0；GT 访问全部走反射能力档案与 Mixin 字符串目标，编译期不解析目标类。
- 双基线编译实证：beta-2（GT5 5.09.54.20）与 beta-3（GT5 5.09.54.133）的
  `compileJava` + `compileTestJava` 均 exit 0（2026-09-11；当时工作树仍是 UILib 4.8，
  HUD 迁移完成后需复测）。同一份源码不存在只在单一基线可编译的静态链接点。
- 上游先例：Qz-UILib 同轮升级采用单基线 CI + 源码级运行期 ABI 分派，不建矩阵。

代价（如实记录）：

- CI 不再有 beta-2 构建门；beta-2 证据改由人工流程产出（临时切 `setGtnhVersion` → 编译 →
  按原始字节恢复并校验 sha256），不能再用 `-P` 覆盖切换（见
  `ERROR-20260911-elytra-manifest-single-source.md`）。
- `verifyGtnhBaseline` 与 `-Pqz.gtnh.*` 一并删除，任何仍引用它们的脚本/文档会直接失败。

## 处置

- 删除 `gradle/gtnh-baselines.json`、`scripts/verify-gtnh-baselines.ps1`，以及
  `build.gradle.kts` 的 `verifyGtnhBaseline` 任务与 `qz.gtnh` 属性逻辑（保留 `VERSION` 注入）。
- `branch-ci.yml` 收敛为唯一 `build` 作业：保留 JDK 列表逻辑、`setupCIWorkspace`、
  `test`/`check`/`build`、`Tags.VERSION` 生成源与主 jar 常量池校验，去掉矩阵与
  `prepare-baselines`；`release-tags.yml` 删除基线解析步骤并去掉全部 `-Pelytra.manifest.version`，
  保留 tag 精确 SHA 门与发布语义。
- 兼容承诺写入 `build.gradle.kts` 与两份 workflow 注释，并带证据等级限定
  （零静态链接 + 双基线编译实证；真机运行态未验证）。

## 教训

- 多基线矩阵的每个基线都是一条上游 maven 依赖链；上游 POM 缺陷（见
  `ERROR-20260817-gtnh-284-baseline-upstream-pom-defect.md`）会以干净 runner 上的解析失败暴露。
  只有在确实存在基线相关断裂点时，矩阵才值得保留。
- 机制退役必须三处同步：构建脚本、CI、文档；并明确替代的证据路径，否则兼容承诺会失去可复现证据。
