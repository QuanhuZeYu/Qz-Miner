# 决策：JitPack 发布依赖边界

## 结论

- Qz-Miner 消费 Qz-UILib 的权威远端依赖源是 JitPack，标准坐标为 `com.github.QuanhuZeYu:Qz-UILib:<tag>:dev`。`<tag>` 必须对应 Qz-UILib GitHub 仓库中不可移动的发布 tag。
- 主动发布到 GTNH Maven / GTNH releases **不是** Qz-Miner 发布前置，也不要求维护者提供 Maven 凭据。GTNH Maven 可作为额外镜像，但其发布成功、失败或 404 均不决定 Miner 是否放行。
- Miner 的依赖放行前置是目标 tag 的 JitPack Build API 显示成功，且同版本 POM、Gradle module metadata 与实际 `dev` classifier jar 均可访问。只看到 GitHub tag、Release、workflow 成功或 Release assets 不足以放行。
- 活动消费者配置不得包含 Maven Local、`flatDir` 旧 group 或 URL 旁路来掩盖 JitPack 失败。发布验证必须证明 clean runner 能以标准坐标从 canonical JitPack 取得制品。
- Miner 继续保持运行时 `required-after:qz_uilib@[4.6.0,)` 下限与开发期 `dev` classifier 要求；发布包不内嵌 UILib。
- 所有 branch push/PR 由只读 clean runner 对同一 SHA 串行执行 `setupCIWorkspace`、`test`、`check`、`build`；该 tag 前证据与 tag 后 Release/assets 核验相互独立。

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

- JitPack Build API 已为 `ok`，对应 commit `93e7ac07b3b57a72ffc45606e07c135ee4971d58`，tag 与 public 仓库身份正确；canonical POM、module metadata 与 main jar 均可访问。
- `dev` 制品已存在于 origin；`www` 或带 query 的请求可返回 `200`、`1993882` bytes，但 canonical 非 `www`、无 query 的普通 GET 在 HKG 边缘仍返回陈旧 `404`。这些结果只说明 origin/边缘缓存暂时分歧，旁路成功不能替代 canonical 门禁。
- Miner 活动配置已切换为 `com.github.QuanhuZeYu:Qz-UILib:4.6.0:dev`，并移除 Maven Local/`flatDir` 旧 group fallback。只有 canonical `dev` 普通 GET 返回 `200 1993882`，且同 SHA branch CI 通过后，才完成 tag 前 clean-consumer 证据。
- tag 推送后还须独立核验 GitHub tag 指向、Release 正文与 jar assets；这些结果不反向替代 JitPack URL 矩阵。

## 变更纪律

未来若放弃 `dev` classifier、改用非 JitPack 权威源或改变 group/tag 规则，属于发布依赖策略变更，须取得新的用户决策，并同步更新发布流程、项目约定、边界与交接状态。
