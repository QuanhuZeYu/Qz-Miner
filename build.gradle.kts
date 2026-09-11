
plugins {
    id("com.github.ElytraServers.elytra-conventions") version("v1.1.2")
    id("com.gtnewhorizons.gtnhconvention")
}

val buildVersion = providers.environmentVariable("VERSION").orElse("5.3.0-dev").get()
version = buildVersion
extra["modVersion"] = buildVersion

// GTNH 基线唯一真源：dependencies.gradle 的 elytraModpackVersion.setGtnhVersion(...) 单值声明（对齐 Qz-UILib）。
// 不再存在 gradle/gtnh-baselines.json 基线清单与 verifyGtnhBaseline 校验任务，
// CI/发布也不再传递 -Pqz.gtnh.expectedGregTechVersion / -Pelytra.manifest.version 覆盖。
//
// 兼容承诺：运行期兼容范围含 GTNH 2.9.0-beta-2 与 2.9.0-beta-3；依据是源码对 GTNH 侧组件零静态链接
// + 双基线编译实证（beta-2/beta-3 均 compileJava/compileTestJava 通过）；真机运行态未验证。
// 源码不得静态链接任一基线独有的上游 API，跨版本差异只能以源码级运行期兼容消化；
// 双基线编译实证按工作站任务笔记的人工流程执行（临时切换基线编译后按原始字节恢复并校验 sha256）。
