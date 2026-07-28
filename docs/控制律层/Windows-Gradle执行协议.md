# Windows Gradle 执行协议

`qz-gradle-opencode/v1` 是 OpenCode agent 在 Windows 上运行有限 Gradle 的唯一协议。agent 入口为 `scripts/run-gradle-opencode.py`，协议状态机实现仍为已审计的 `scripts/run-gradle-opencode.ps1`。仓根与 wrapper 从脚本位置推导；运行产物只写 `%TEMP%/opencode/qz-gradle-v1-<RunId>.*`，共享 `qz-gradle-opencode-v1.active.lock`。

`Start` 不接受调用方 RunId，生成独立的 GUID RunId 与 invocationId。它先在固定 mutation guard 下创建含完整 owner token 的共享锁，再原子发布不含完整参数的 `PREPARED` metadata，最后启动 launcher；启动后的 `RUNNING` 更新使用原子 Replace。launcher 分离 stdout/stderr，并以 invocationId 绑定 pending/exit sentinel。

`Poll`/`Wait` 先按本 Run metadata 解析 canonical exit；有效终态可重复 Poll，且不会删除其他 Run 的新锁。没有有效终态时，才在 mutation guard 内要求 metadata 与完整 active-lock token（protocol/RunId/invocationId）一致，再核对 `RUNNING` PID 与启动时间。terminal 判别为 `NONE/VALID/STALE_IDENTITY/CORRUPT`，只有 `VALID` 可读取退出码或释放锁；损坏、缺失或身份不一致均返回 `INCOMPLETE` 并保锁。

launcher 先完整写入 `.writing`，再依次原子移动为 pending 与 canonical exit；Poll 从不读取 `.writing`。仅当 `RUNNING` metadata 所指 launcher 已确认退出后，Poll 才在 guard 内复读 pending 身份/schema并尝试晋升；晋升后须复读 canonical exit 为 `VALID` 才释放锁。`PREPARED` 不推断进程退出，返回 `STARTED_UNCONFIRMED/78` 并保锁。

进程成功启动是不可逆边界：此后 metadata 或启动时间读取失败只返回安全诊断并保留锁与 launcher。Start 在取得锁后、发布 `PREPARED` 前若发生进程级崩溃，可能永久留下保守锁；协议不自动解锁、不 kill，也不引入 startup fencing。遇到此类状态应人工只读检查 active lock、metadata、launcher 与进程身份后决定处置。metadata 损坏时 Poll 会结合 lock owner 输出 `activeRunId`，但不会自动恢复。

## 退出码

- Start 成功 `0`；Poll 运行中 `3`；参数拒绝 `64`；协议内部错误 `74`；锁冲突 `75`；环境异常或孤儿 `78`；Wait 窗口到期或执行超时 `124`。
- 终态成功为 `0`，Gradle 失败传播其退出码。单行 JSON 同时给出 `status`、`protocolExitCode`，Gradle 终态另给 `gradleExitCode`，用于消歧恰好撞上协议保留码的 Gradle 退出码。

## 安全边界

- `gradleArgs` 使用大小写敏感的严格 allowlist：任务仅 `compileJava`、`test`、`check`、`build`、`publishToMavenLocal`（qualified task path 取末段后仍须命中）；无值选项仅 `--offline`、`--no-configuration-cache`；`--tests` 必须位于已选择 `test` 任务之后并紧跟安全 Java 类/方法通配值；项目属性仅 `-Pgtnh.settings.blowdryerTag=<安全值或精确空值>`。其余选项、任务和 response file 一律在产物创建前拒绝。metadata 的 taskSummary 只记录规范任务名、参数数量和布尔选项，不记录 test filter。脚本统一 plain console。
- 只读验证 `GRADLE_USER_HOME` 非空、绝对 ASCII 且目录存在，不回显、不修改环境。
- 不接受 executable、workdir、log、environment、kill 参数。默认 `build` 按改动风险使用 `Start/Poll/Wait/SelfTest`；禁止直接 wrapper 或自造进程。
- `runClient*`/`runServer*` 交用户；`verify-gtnh-baselines.ps1` 暂不授权。

## 命令

```text
python scripts/run-gradle-opencode.py start compileJava
python scripts/run-gradle-opencode.py poll <RunId>
python scripts/run-gradle-opencode.py wait <RunId> --seconds 30
python scripts/run-gradle-opencode.py self-test
```

Python 入口把动作、Gradle 参数数组、RunId 与等待秒数序列化为 Base64 JSON；固定桥接代码在同一 PowerShell 7 进程内还原强类型参数并调用协议实现。Python 使用 `subprocess` 参数列表、`shell=False`、固定 cwd 和有界超时，不拼接用户参数到 PowerShell 命令，不使用外层 `pwsh -File ... -GradleArgs` 数组绑定。

SelfTest 不调用真实 Gradle，也不在仓库写产物。它仅在当前 PowerShell 进程内临时替换测试所需脚本变量，并在 `finally` 恢复；生产入口没有环境 fixture、runtime/wrapper override 或跳过环境检查的隐藏入口。跨进程 guard 测试使用 SelfTest 临时生成的最小 helper，不进入生产 CLI。

Qz-UILib 已退役 agent 本机 Gradle 能力，不再持有该协议脚本；本协议只属于 Qz-Miner。Python 适配器不得复制锁、metadata、launcher、退出码或 allowlist 逻辑，所有协议语义继续由单一 PowerShell 实现负责。
