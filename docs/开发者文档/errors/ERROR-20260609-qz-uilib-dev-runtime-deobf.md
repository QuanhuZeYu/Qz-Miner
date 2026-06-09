# 错误记录：Qz-UILib 发布 jar 误入 dev runtime

## 错误现象

- `./gradlew.bat runClient21` 在客户端 `PREINITIALIZATION` 阶段失败。
- FML 标记 `qz_uilib` 为 `UCE`，堆栈指向 `DevToolsClientBootstrap.registerClientDevTools(...)`。
- 直接异常为 `NoSuchMethodError: net.minecraftforge.client.ClientCommandHandler.func_71560_a(net.minecraft.command.ICommand)`。
- `Qz-UILib-4.2.0-sources.jar` 中源码实际调用 `ClientCommandHandler.instance.registerCommand(...)`，不是直接写 `func_71560_a(...)`。

## 触发场景

- 将 Qz-UILib 升级到 `4.2.0` 后，使用 `devOnlyNonPublishable("com.github.QuanhuZeYu:Qz-UILib:4.2.0")` 直接接入无后缀发布 jar。
- 在 RFG / MCP 命名的开发运行环境中启动 `runClient21`。

## 根本原因

- Qz-UILib 无后缀发布 jar 内部调用的是 1.7.10 发布态 / SRG 命名方法 `ClientCommandHandler.func_71560_a(...)`。
- RFG dev runtime 中 Forge 类使用 MCP 命名，发布态 jar 会在运行时寻找不存在的 SRG 方法。
- Qz-UILib `4.2.0` 实际发布了 `Qz-UILib-4.2.0-dev.jar`，本项目应优先依赖 dev classifier，而不是无后缀发布 jar。

## 修复方案

- 将 Qz-UILib 开发依赖改为 `devOnlyNonPublishable("com.github.QuanhuZeYu:Qz-UILib:4.2.0:dev") { transitive = false }`。
- 保持该依赖为 non-publishable，避免写入 Qz-Miner 发布依赖。

## 预防措施

- 后续把外部 1.7.10 mod 加入 dev runtime 时，优先依赖 `dev` classifier 或 Gradle metadata 中的 MCP 变体。
- 若上游没有 dev jar，再考虑 `rfg.deobf(...)` 作为兜底重映射方案。
- 若运行时报 `NoSuchMethodError` 且方法名形如 `func_...`，优先排查是否误用了无后缀发布 jar。
- 编译通过不能覆盖此类运行态命名问题，涉及外部 mod 发布 jar 升级后需要至少跑一次对应 run task。
