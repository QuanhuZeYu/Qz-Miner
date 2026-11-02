# Qz-Miner

面向 Minecraft 1.7.10 / Forge / GTNH 的连锁挖矿模组，提供 CHAIN、AREA、INTERACT 与按可选 Mod 注册的 SPECIAL。

- 基线：`5.5.0`，GTNH `2.9.0`，Java 8 字节码。
- 依赖：同版本的 Qz-UILib 5.0 制品；Core 与字体适配 Java 8 经 Maven，Minecraft 1.7.10 宿主经正式 Release 或显式 Maven。发布包不内嵌，由整合包独立安装。
- 操作：默认连锁键为 `~` 所在键位，可在按键设置修改；按住连锁键使用当前模式，滚轮切子模式，叠加潜行键切主模式，各主模式记忆子模式。该组合独占滚轮，打开 GUI 时保留原版行为。
- 配置：编辑 `config/qz_miner.yaml`；服务端权限等级 4 可使用 `/qzminer config list|get|set|reload`。

默认消费 UILib `5.0.0-beta4`：Core 与字体适配从 Maven Central 解析，Minecraft 宿主从同版本 GitHub Release 解析。本地组合可显式传入 `-PuilibRepository=<Maven URI>` 和 `-PuilibVersion=<版本>`，三者使用同版制品。升级后通过 HUD 编辑页重新设置旧版位置与缩放；对象组成员使用文本 selector 列表编辑。客户端与服务端统一使用 `5.5.x`。

合同与验收边界见[正式规格](<specs/README.md>)；协作入口见 [AGENTS](<AGENTS.md>)。
