# 决策：JitPack 发布依赖边界

## 结论

- Qz-Miner 消费 Qz-UILib 的权威远端依赖源是 JitPack，标准坐标为 `com.github.QuanhuZeYu:Qz-UILib:<tag>:dev`。`<tag>` 必须对应 Qz-UILib GitHub 仓库中不可移动的发布 tag。
- 主动发布到 GTNH Maven / GTNH releases **不是** Qz-Miner 发布前置，也不要求维护者提供 Maven 凭据。GTNH Maven 可作为额外镜像，但其发布成功、失败或 404 均不决定 Miner 是否放行。
- Miner 的依赖放行前置是目标 tag 的 JitPack Build API 显示成功，且同版本 POM、Gradle module metadata 与实际 `dev` classifier jar 均可访问。只看到 GitHub tag、Release、workflow 成功或 Release assets 不足以放行。
- Maven Local 仅供本地开发，不得作为发布验收或掩盖 JitPack 失败。发布验证必须证明干净的远端消费者能够取得 JitPack 制品。
- Miner 继续保持运行时 `required-after:qz_uilib@[4.6.0,)` 下限与开发期 `dev` classifier 要求；发布包不内嵌 UILib。

## 放行 URL 矩阵

以 `<tag>` 替换目标版本；四项均通过才视为 JitPack 制品可消费：

| 检查项 | 权威 URL / 期望 |
|---|---|
| Build API | `https://jitpack.io/api/builds/com.github.QuanhuZeYu/Qz-UILib/<tag>`，目标版本状态为成功 |
| POM | `https://jitpack.io/com/github/QuanhuZeYu/Qz-UILib/<tag>/Qz-UILib-<tag>.pom`，可访问 |
| Gradle module metadata | `https://jitpack.io/com/github/QuanhuZeYu/Qz-UILib/<tag>/Qz-UILib-<tag>.module`，可访问 |
| `dev` classifier | `https://jitpack.io/com/github/QuanhuZeYu/Qz-UILib/<tag>/Qz-UILib-<tag>-dev.jar`，可访问且为实际目标制品 |

`4.5.2` 的 POM、module 与 `dev` jar 曾按上述路径真实可访问，证明该 classifier 可以由 JitPack 发布；这不代表后续 tag 自动通过。

## Tag 与失败处理

- 已发布 tag 不移动、不覆盖。若确定性缺陷需要源码修复，应提交修复并创建新 tag，再按完整 URL 矩阵验收。
- JitPack 构建失败时先按失败阶段区分源码、构建脚本、wrapper 权限和 runner 外部网络；不能因某个仓库域解析失败就武断归因为“缺仓库”。
- 同一 tag 可因 JitPack runner 对不同外部域的瞬时可达性而推进到不同深度；只有稳定复现到同一确定性构建错误，才归因于源码或构建配置。

## 当前 `4.6.0` 状态

- GitHub tag、Release 与 CI 已完成，但 JitPack Build API 为 `Error`，POM、module、main jar 与 `dev` jar 均为 404，因此 `4.6.0` 尚不可按权威坐标消费，Miner 发布继续暂停。
- 连续三次构建分别在 Maven Central / plugin 依赖、Sponge `lzma:lzma:0.0.1`、Minecraft Libraries LWJGL 阶段失败，错误共同为 `Temporary failure in name resolution`，且每次推进深度不同。主阻断是 JitPack runner 跨外部域的 DNS / 依赖可达性，不是源码编译失败或缺少仓库声明。
- RFG 已注入 Sponge、Minecraft Libraries 与 Forge 仓库，并显式依赖 `lzma:lzma:0.0.1`；该坐标不在 Maven Central 属正常事实，Sponge 与 `libraries.minecraft.net` 的对应 POM 可从外部访问。
- `4.6.0` tag 中 `gradlew` 为 mode `100644`，导致 `before_install` 稳定出现 `Permission denied`。JitPack 随后仍执行 `install` 并继续到外部依赖解析，因此这是下一个版本应修复的独立确定性缺陷，不是当前三次构建的最终失败点。
- Miner 当前仍配置旧 `club.heiqi.uilib` 坐标并带 Maven Local fallback，尚未切换标准 JitPack group；该代码纠偏另立实现任务，本决策不把现状误写为已完成。

## 变更纪律

未来若放弃 `dev` classifier、改用非 JitPack 权威源或改变 group/tag 规则，属于发布依赖策略变更，须取得新的用户决策，并同步更新发布流程、项目约定、边界与交接状态。
