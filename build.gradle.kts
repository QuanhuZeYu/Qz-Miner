plugins {
    id("com.github.ElytraServers.elytra-conventions") version("v1.1.2")
    id("com.gtnewhorizons.gtnhconvention")
}

val buildVersion = providers.environmentVariable("VERSION").orElse("5.5.1-dev").get()
version = buildVersion
extra["modVersion"] = buildVersion

apply(from = "gradle/network-version.gradle.kts")
apply(from = "gradle/shaders.gradle.kts")

// 既有全量 mesh 容量验证使用独立测试堆上限。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "2g"
    systemProperty("qz.uilib.version", providers.gradleProperty("uilibVersion").get())
}
