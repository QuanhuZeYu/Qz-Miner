# ERROR-20260912：被引用的 UILib 制品「拒绝自身版本」→ 集成服务器握手拒绝，进入世界即被卸载

## 症状

Miner dev 环境（`runClient`）能正常启动到主菜单，但**进入世界瞬间被踢回**，且不生成 crash-report
（`run/client/crash-reports/` 无新文件，也无 `hs_err_pid`）。用户口径：「无法进入世界」。

`run/client/logs/fml-client-*.log` 的关键序列：

```
[09:03:06] [Client thread/ERROR] [FML/qz_uilib]: The mod qz_uilib appears to reject its own version number (4.10.0) in its version handling. This is likely a severe bug in the mod!
[09:04:38] [Netty IO #1/INFO] [FML/]: Client attempting to join with 79 mods : qz_uilib@4.10.0, ...
[09:04:38] [Netty IO #1/INFO] [FML/]: Attempting connection with missing mods [] at CLIENT
[09:04:38] [Netty IO #1/INFO] [FML/]: Rejecting connection CLIENT: [FMLMod:qz_uilib{4.10.0}]
[09:04:40] [Server thread/INFO] [FML/]: Unloading dimension 0 … -1033
```

## 根因

`libs/qz_uilib-4.10.0-dev.jar` 里 `club.heiqi.uilib.MyMod` 的 `acceptableRemoteVersions` 为
`[4.9.0,4.10.0)`（发布 4.9.0 时写下），而制品自身版本（`mcmod.info version` / `Tags.VERSION`）是
`4.10.0` —— 开上界恰好排除自身。FML 在**本端与远端版本字符串相同**时仍执行区间检查，于是服务端拒绝
客户端连接，集成服务器随后卸载全部维度。

注意本条与本仓既有断言的分工缺口：`QzUiLibArtifactContractTest` 只校验
「jar 内部版本满足 **Miner `@Mod` 依赖区间** `[4.9.1,5.0.0)`」——4.10.0 确实满足，所以**全绿通过**；
没有任何断言读过**jar 自己的远端版本区间**。同理 UILib 侧 538 类 / 5598 例也全绿。

## 处置

1. UILib 侧声明上抬为 `[4.9.0,5.0.0)`（与 Miner 依赖上界成对同源，兼容面只增不减），
   并加源码守卫 `FmlRemoteVersionCompatibilityContractTest#declaredRangeMustContainOwnBuildVersion`。
2. 本仓补制品守卫 `QzUiLibArtifactContractTest#referencedQzUiLibArtifactAcceptsItsOwnVersion`：
   被引用的 UILib jar 自己的远端区间必须接受它自己的 `mcmod.info` 版本（空串=开发期精确匹配，跳过）。
3. `dependencies.gradle` 段注释记录新制品 sha256 `A24A1532…`（3925676 bytes），并禁止回退到
   `104B6940…`（3925675 bytes，即携带 `[4.9.0,4.10.0)` 的那一件）。

## 教训

制品契约不能只验「上游版本满足下游依赖」，还要验「**上游制品自身是否自洽**」。前者通过而后者失败时，
故障在两个仓库的全部单元测试之外，只在真机握手的最后一刻暴露。
