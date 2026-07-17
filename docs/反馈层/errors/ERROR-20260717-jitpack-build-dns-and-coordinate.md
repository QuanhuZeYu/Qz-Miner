# JitPack 构建 DNS、wrapper mode 与消费者坐标混淆

## 错误现象

- Qz-UILib `4.6.0` 的 GitHub tag、Release 与 CI 均已完成，但 JitPack Build API 为 `Error`，POM、Gradle module metadata、main jar 与 `dev` classifier jar 均返回 404。
- 同一 tag 连续触发三次 JitPack 构建，分别推进到 Maven Central / plugin 依赖、Sponge `lzma:lzma:0.0.1`、Minecraft Libraries LWJGL 解析阶段后失败；共同错误为 `Temporary failure in name resolution`。
- `gradlew` 在 tag 中为 mode `100644`，`before_install` 稳定 `Permission denied`；Miner 同时仍使用旧 `club.heiqi.uilib` 坐标与 Maven Local fallback。

## 触发场景

以 GitHub Release 或本机 Maven Local 成功推断发布依赖可用，或把不同阶段出现的外部依赖解析失败统一判断为“缺仓库 / 源码失败”，会混淆三条独立问题链：

1. JitPack runner 对多个外部仓库域的 DNS / 网络可达性。
2. Qz-UILib wrapper 的可执行文件 mode。
3. Qz-Miner 消费者使用的 group 与 Maven Local fallback。

## 根本原因

### 主阻断：JitPack runner 外部 DNS / 依赖可达性

三次构建命中不同外部域、每次推进深度不同，但都以 `Temporary failure in name resolution` 结束，说明主阻断位于 runner 的跨域 DNS / 依赖可达性，而不是稳定的源码编译错误。RFG 已注入 Sponge、Minecraft Libraries 与 Forge 仓库，并显式依赖 `lzma:lzma:0.0.1`；Maven Central 没有该坐标是正常事实，Sponge 与 `libraries.minecraft.net` 的对应 POM 从外部可访问，不能据此补错仓库。

### 独立确定性缺陷：wrapper mode

`gradlew` mode `100644` 会让 `before_install` 稳定报 `Permission denied`。但 JitPack 随后仍执行 `install` 并进入依赖解析，所以它需要在新版本修复，却不是当前连续失败的最终阻断点。

### 独立消费者缺陷：旧坐标与本地回退

Qz-UILib 的 JitPack 标准坐标是 `com.github.QuanhuZeYu:Qz-UILib:<tag>:dev`。Miner 当前旧 `club.heiqi.uilib` 坐标与 Maven Local fallback 可让本机成功但远端消费者失败，不能证明 JitPack 发布有效。

## 修复方案

- 保持 `4.6.0` tag 不移动；等待或重新触发外部可达性恢复后的 JitPack 构建。若需修 wrapper mode，在新提交修正可执行位并创建新 tag，不覆盖既有 tag。
- 后续实现任务将 Miner 切换到 JitPack 标准 group，并避免 Maven Local fallback 掩盖发布验收；本次文档固化不改 Gradle。
- 放行 Miner 前逐项验证：Build API 成功、POM 可访问、`.module` 可访问、实际 `-dev.jar` 可访问。GitHub Release assets 与 GTNH Maven 状态均不替代这些证据。

## 预防措施

- 发布依赖策略以 `docs/反馈层/决策/jitpack-release-dependencies.md` 为权威；主动 GTNH Maven 发布不是前置，也不再索要 Maven 凭据。
- 诊断 JitPack 时记录失败域、阶段、错误类型和多次构建推进深度，先分清瞬时 runner 故障与稳定源码 / 脚本错误。
- 发布验收不得启用 Maven Local 证明远端可消费；必须直接核验目标 tag 的 JitPack URL 矩阵。
- 已发布 tag 坚持不可移动；任何确定性修复通过新 tag 交付。
