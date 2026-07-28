# 决策：JitPack 发布依赖边界

## 结论

- Qz-Miner 消费 Qz-UILib 的权威远端依赖源是 JitPack，标准坐标为 `com.github.QuanhuZeYu:Qz-UILib:<tag>:dev`。`<tag>` 必须对应 Qz-UILib GitHub 仓库中不可移动的发布 tag。
- 主动发布到 GTNH Maven / GTNH releases **不是** Qz-Miner 发布前置，也不要求维护者提供 Maven 凭据。GTNH Maven 可作为额外镜像，但其发布成功、失败或 404 均不决定 Miner 是否放行。
- Miner 的依赖放行采用 channel-aware 规则。当前 Qz-UILib JitPack 组合发布通道要求目标 tag 的 Build API 身份正确，同版本 POM、main jar、实际 `dev` classifier jar、`sources` jar 及校验和均可访问，并有 clean 显式 `:dev` consumer 证据；只看到 GitHub tag、Release、workflow 成功或 Release assets 不足以放行。
- 活动消费者配置不得包含 Maven Local、`flatDir` 旧 group 或 URL 旁路来掩盖 JitPack 失败。发布验证必须证明 clean runner 能以标准坐标从 canonical JitPack 取得制品。
- Miner 继续保持运行时 `required-after:qz_uilib@[4.6.0,)` 下限与开发期 `dev` classifier 要求；发布包不内嵌 UILib。
- 所有 branch push/PR 由只读 clean runner 对同一 SHA 串行执行 `setupCIWorkspace`、`test`、`check`、`build`；该 tag 前证据与 tag 后 Release/assets 核验相互独立。

## JitPack 组合发布通道证据矩阵

以 `<tag>` 替换目标版本；制品请求必须使用非 `www`、无 query 的 canonical URL，普通 GET 返回预期状态且响应为真实目标内容。所有必需证据均通过才视为 JitPack 制品可消费：

| 检查项 | 权威 URL / 期望 |
|---|---|
| Build API | `https://jitpack.io/api/builds/com.github.QuanhuZeYu/Qz-UILib/<tag>`，目标版本状态为 `ok`，tag、public 仓库身份与 commit 均正确 |
| POM | `https://jitpack.io/com/github/QuanhuZeYu/Qz-UILib/<tag>/Qz-UILib-<tag>.pom`，返回 `200` 且为实际目标 POM |
| main jar | `https://jitpack.io/com/github/QuanhuZeYu/Qz-UILib/<tag>/Qz-UILib-<tag>.jar`，返回 `200` 且为实际目标制品 |
| `dev` classifier jar | `https://jitpack.io/com/github/QuanhuZeYu/Qz-UILib/<tag>/Qz-UILib-<tag>-dev.jar`，返回 `200` 且为实际目标制品 |
| `sources` classifier jar | `https://jitpack.io/com/github/QuanhuZeYu/Qz-UILib/<tag>/Qz-UILib-<tag>-sources.jar`，返回 `200` 且为实际目标制品 |
| 制品校验和 | POM/main/`dev`/`sources` 的 canonical `.sha1` 返回 `200` 并与下载内容一致；关键制品另记录稳定 SHA-256 |
| Gradle module metadata | `https://jitpack.io/com/github/QuanhuZeYu/Qz-UILib/<tag>/Qz-UILib-<tag>.module` 返回 `404` 是该组合发布通道的预期结果，不构成失败 |
| clean consumer | 无 Maven Local、`flatDir`、旧 group 或 main fallback，以 `com.github.QuanhuZeYu:Qz-UILib:<tag>:dev` 成功解析并消费 |

普通 Maven 仓库若明确发布 Gradle Module Metadata，应按该 GMM 通道要求 `.module=200` 与变体身份正确；该规则不得混入上述 JitPack 组合发布通道，也不能用 `.module=404` 推翻已由 POM/classifier 与 clean consumer 证明的可消费性。

## Tag 与失败处理

- 已发布 tag 不移动、不覆盖。若确定性缺陷需要源码修复，应提交修复并创建新 tag，再按完整 URL 矩阵验收。
- JitPack 构建失败时先按失败阶段区分源码、构建脚本、wrapper 权限和 runner 外部网络；不能因某个仓库域解析失败就武断归因为“缺仓库”。
- 同一 tag 可因 JitPack runner 对不同外部域的瞬时可达性而推进到不同深度；只有稳定复现到同一确定性构建错误，才归因于源码或构建配置。

## 当前 `4.6.3` 状态

- annotated tag peeled commit 为 `c2d3ea91173fbedaeb912f3f07e8dcfce55bfc43`；Release 与 workflow `29554928156` 成功，JitPack Build API 为 `ok`，isTag/public/commit 身份正确，build log 可访问。
- canonical POM、main、`dev`、`sources` 及其 `.sha1` 均返回 `200`；`.module` 返回预期 `404`。clean consumer 已用显式 `:dev` 坐标通过，`dev` SHA-256 为 `76319a864734d5770d1bf8579b7ab03f16be52453c92b662b9aae9010b898493`。
- Miner 活动配置为 `com.github.QuanhuZeYu:Qz-UILib:4.6.3:dev`，保持 `transitive=false` 并移除 Maven Local/`flatDir` 旧 group fallback。GitHub 正式 Release、JitPack `ok` tag/commit 身份与 canonical POM 已确认；本仓实际 `:dev` 解析和构建证据以当前任务结果为准，完整 POM/main/`dev`/`sources` 校验和、clean consumer 与同 SHA branch CI 仍分别记录。
- tag 推送后还须独立核验 GitHub tag 指向、Release 正文与 jar assets；这些结果不反向替代 JitPack URL 矩阵。

## 变更纪律

未来若放弃 `dev` classifier、改用非 JitPack 权威源或改变 group/tag 规则，属于发布依赖策略变更，须取得新的用户决策，并同步更新发布流程、项目约定、边界与交接状态。
