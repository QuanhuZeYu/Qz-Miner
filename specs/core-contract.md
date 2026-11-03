# 核心职责与边界

状态：正式规格，适用 `5.5.1`。

## 职责

Miner 维护 CHAIN / AREA / INTERACT / SPECIAL 的业务、服务端执行权威、网络、掉落及宿主接入；Qz-UILib 提供通用配置与 UI 能力。依赖只从 Miner 指向 UILib，业务不得复制通用框架或绕开已有抽象。

运行基线为 Minecraft 1.7.10 / Forge、GTNH 2.9.0、Java 8 字节码。UILib 5.0 使用同一明确版本的制品：Core 与 Platform 字体适配 Java 8 经 Maven，Minecraft 1.7.10 宿主经正式 Release 或显式 Maven。构建不读取上游 classes，发布包不内嵌 UILib。设置模板和 HUD 以同版 `5.0.0-beta5` 组合为基线，默认从公开渠道解析，本地组合可显式提供 Maven 仓库；运行依赖下界保留 `5.0.0-beta4`，包含可与持续位移组合的声明式入场、跨父布局运动换基与原生像素 HUD。

世界、worker、掉落与事务边界归[挖矿生命周期](<mining-lifecycle.md>)；权威配置与稳定文本归[配置](<configuration.md>)；客户端能力与资源归[客户端接入](<client-integration.md>)。内部类、算法、队列、parser 与事务端口不是第三方 Java API。

## 联机兼容

- 构建从制品的 major.minor 派生 `[X.Y.0-alpha,X.(Y+1).0-alpha)`，写入 `@Mod.acceptableRemoteVersions`，采用 FML 原生 Maven 版本序；不得声明抢占区间的 `@NetworkCheckHandler`。
- 按区间比较 patch、prerelease 与 qualifier，不附加严格 SemVer 字符串 checker；例如 `X.Y` 按 Maven 语义等价于 `X.Y.0`。相邻区间不互通，5.5 与 5.4 拒绝互通。
- 远端表缺少精确 `qz_miner` key 时 FML 双向放行；这只表示模组表检查放行，不保证无 Mod 对端的 channel 或业务发送安全。
- 同一区间保持 packet discriminator/Side、wire framing、ordinal/code/mask 与已发布 schema 的兼容；不兼容变更必须换 minor 或 major 区间。新增配置键或放宽合法域不视为破坏。

## 验收

- 实际制品声明接受自身、接受区间内版本并拒绝边界及旧 major；missing key 与 Maven 等价写法遵循 FML，不能用自造 checker 替代。
- 完整 build 使用真实 UILib 5.0 制品；dedicated server 的公共类加载不依赖客户端专属类型，可选 Mod 缺失可启动。
- 自动化、静态核验与游戏实机分别报告。mixed-patch、missing client、dedicated server 和真实第三方 Mod 组合须有对应运行证据，build 不代表实机通过。
