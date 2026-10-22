plugins {
    id("com.github.ElytraServers.elytra-conventions") version("v1.1.2")
    id("com.gtnewhorizons.gtnhconvention")
}

val buildVersion = providers.environmentVariable("VERSION").orElse("6.0.0-dev").get()
version = buildVersion
extra["modVersion"] = buildVersion

apply(from = "gradle/network-version.gradle.kts")
apply(from = "gradle/shaders.gradle.kts")

// 全量预览 mesh 容量回归需要足够堆；不会改变生产运行的内存策略。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "2g"
    systemProperty("qz.uilib.version", providers.gradleProperty("uilibVersion").get())
}
