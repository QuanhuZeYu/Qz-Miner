# UILib 4.7.0 本地 devjar 构建：devJar task 移除与 Github manifest 拉取超时

## 错误现象

为给 Qz-Miner 提供 Qz-UILib 4.7.0 本地 devjar（相关提交 `91c4c60`），构建 UILib tag 4.7.0 临时 worktree 时连续踩到两个坑：

1. 找不到 `devJar` task：按旧经验执行 devjar 构建任务时报任务不存在，构建无法产出 dev 分类 jar。
2. gtnhconvention 插件拉取 DreamAssemblerXXL manifest 失败，报 `Failed to load the manifest from Github`；本机直连 `raw.githubusercontent.com` 出现 TLS 握手超时。

## 触发场景

- UILib 使用 gtnhgradle 2.0.25（gtnhsettingsconvention 2.0.25）之后，在 tag 临时 worktree 中按旧版本流程执行 `devJar` 任务。
- 本机网络环境对 `raw.githubusercontent.com` 存在 Fake-IP 干扰（198.18.x 段），构建进程直连该域名超时。

## 根本原因

1. gtnhgradle 2.0.25 已移除 `devJar` task；dev jar 实际由 `jar` / `publishToMavenLocal` 任务链产出，不再有独立 devJar 入口。
2. gtnhconvention 插件初始化时需要从 `raw.githubusercontent.com` 拉取 DreamAssemblerXXL 的 manifest，本机 Fake-IP 解析导致 TLS 握手超时，且没有仓库级代理配置兜底。

## 修复方案

1. 改为执行 `call gradlew.bat --no-configuration-cache publishToMavenLocal -x test`，成功产出 `build/libs/qz_uilib-4.7.0-dev.jar`（约 2.18 MB），再由 Miner 的 libs 本地依赖消费。
2. 仅对构建进程注入 `GRADLE_OPTS=-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10809`（本机代理端口），绕过 Fake-IP 干扰完成 manifest 拉取；未修改仓库内容、未持久化环境变量。

## 预防措施

- UILib 本地构建遇 `Failed to load the manifest from Github` 时，优先按本记录通过 `GRADLE_OPTS` 为构建进程注入本机代理，不要改动仓库或全局环境变量。
- UILib 4.7.0+ 本地构建 devjar 不再使用 `devJar` task，统一走 `publishToMavenLocal` 任务链并核对 `build/libs` 下的 `-dev.jar` 产物。
- 本地 devjar 属于 dev runtime 依赖，接入 Miner 时保持 non-publishable 策略，与 `ERROR-20260609-qz-uilib-dev-runtime-deobf.md` 的结论一致。

- 日期：2026-08-14
- 相关提交：`91c4c60`（UILib 依赖升级 4.7.0 本地 devjar）
