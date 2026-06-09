# 错误记录：Qz-UILib 发布 jar 未经 RFG deobf 进入 dev runtime

## 错误现象

- `./gradlew.bat runClient21` 在客户端 `PREINITIALIZATION` 阶段失败。
- FML 标记 `qz_uilib` 为 `UCE`，堆栈指向 `DevToolsClientBootstrap.registerClientDevTools(...)`。
- 直接异常为 `NoSuchMethodError: net.minecraftforge.client.ClientCommandHandler.func_71560_a(net.minecraft.command.ICommand)`。

## 触发场景

- 将 Qz-UILib 升级到 `4.2.0` 后，使用 `devOnlyNonPublishable("com.github.QuanhuZeYu:Qz-UILib:4.2.0")` 直接接入发布 jar。
- 在 RFG / MCP 命名的开发运行环境中启动 `runClient21`。

## 根本原因

- Qz-UILib 发布 jar 内部调用的是 1.7.10 发布态 / SRG 命名方法 `ClientCommandHandler.func_71560_a(...)`。
- RFG dev runtime 中 Forge 类使用 MCP 命名，未经 `rfg.deobf(...)` 重映射的外部发布 jar 会在运行时寻找不存在的 SRG 方法。
- 这不代表上游 Qz-UILib 发布 jar 本身错误；发布态 mod jar 保留 SRG 命名是正常现象，错误在于本项目开发运行依赖接入时没有执行 deobf 重映射。

## 修复方案

- 将 Qz-UILib 开发依赖改为 `devOnlyNonPublishable(rfg.deobf("com.github.QuanhuZeYu:Qz-UILib:4.2.0")) { transitive = false }`。
- 保持该依赖为 non-publishable，避免写入 Qz-Miner 发布依赖。

## 预防措施

- 后续把发布态 1.7.10 mod jar 加入 dev runtime 时，优先确认是否需要 `rfg.deobf(...)`。
- 若运行时报 `NoSuchMethodError` 且方法名形如 `func_...`，优先排查外部发布 jar 是否未经 RFG deobf 进入 MCP 命名运行环境。
- 编译通过不能覆盖此类运行态命名问题，涉及外部 mod 发布 jar 升级后需要至少跑一次对应 run task。
