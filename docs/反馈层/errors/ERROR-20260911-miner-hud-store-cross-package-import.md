# ERROR-20260911：HUD 持久化 store 跨包接线漏 import（compileJava 找不到符号）

**日期**：2026-09-11　**组件**：Qz-Miner `ClientProxy.init` 接线 + `client/MinerHudLayoutStore`（task-12）
**状态**：已修复（重跑锁脚本 `build` 绿：150 套件 / 827 用例 / 0 失败）

## 现象

新增 HUD 布局持久化接线后首次 `gradlew build`，`compileJava` 失败 2 errors：

```
ClientProxy.java:159: 错误: 找不到符号
        HudLayoutPersistence.install(new MinerHudLayoutStore(MinerHudLayoutStore.resolveConfigDir()));
                                         ^ 符号: 类 MinerHudLayoutStore
                                                             ^ 符号: 变量 MinerHudLayoutStore
```

store 源文件在 `src/main/java/club/heiqi/qz_miner/client/MinerHudLayoutStore.java`，同一轮构建里
它其实已编译成功（`build/classes/java/main/.../MinerHudLayoutStore.class` 存在），只有接线点解析不到
这个名字。构建日志：`temp/logs/miner-20260911-173200.log`。

## 原因

接线点 `ClientProxy` 的包是 `club.heiqi.qz_miner`，实现类在**子包** `club.heiqi.qz_miner.client`；
两者不同包，必须显式 import。初版接线（连同已评审的接线草案）只加了接口的
`import club.heiqi.uilib.ui.hud.api.HudLayoutPersistence;`，漏了实现类的跨包 import。

## 修复

在 `ClientProxy.java:25` 的 client 包 import 块内补一行：

```java
import club.heiqi.qz_miner.client.MinerHudLayoutStore;
```

接线草案文档同步更正为「**两个** import + 一行调用」并加踩坑注记；重跑锁脚本 `build` 成功
（14s，`temp/logs/miner-20260911-173315.log`）。

## 教训

跨包接线草案评审时，要把「被引用类型的 import」逐条对着**包路径**核一遍，别只核接口名——
父包接线点引用子包新类时，少写的那一行 import 只会在编译期暴露。
