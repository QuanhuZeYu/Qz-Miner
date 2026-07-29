
plugins {
    id("com.github.ElytraServers.elytra-conventions") version("v1.1.2")
    id("com.gtnewhorizons.gtnhconvention")
}

val buildVersion = providers.environmentVariable("VERSION").orElse("5.2.1-dev").get()
version = buildVersion
extra["modVersion"] = buildVersion

val expectedGregTechVersion = providers.gradleProperty("qz.gtnh.expectedGregTechVersion").orElse("")
val resolvedGregTechVersions = configurations.named("compileClasspath").map { compileClasspath ->
    compileClasspath.incoming.resolutionResult.allComponents
        .mapNotNull { it.moduleVersion }
        .filter {
            it.group == "com.github.GTNewHorizons" && it.name == "GT5-Unofficial"
        }
        .map { it.version }
}

tasks.register("verifyGtnhBaseline") {
    group = "verification"
    description = "验证 compileClasspath 解析到清单指定的唯一 GregTech 版本"
    inputs.property("expectedGregTechVersion", expectedGregTechVersion)
    inputs.property("resolvedGregTechVersions", resolvedGregTechVersions)

    doLast {
        val expected = inputs.properties.getValue("expectedGregTechVersion") as String
        if (expected.isEmpty()) {
            throw GradleException("Missing required property: qz.gtnh.expectedGregTechVersion")
        }
        @Suppress("UNCHECKED_CAST")
        val actualVersions = inputs.properties.getValue("resolvedGregTechVersions") as List<String>

        if (actualVersions.size != 1) {
            throw GradleException(
                "Expected exactly one com.github.GTNewHorizons:GT5-Unofficial component, " +
                    "but found ${actualVersions.size}: ${actualVersions.joinToString()}",
            )
        }

        val actual = actualVersions.single()
        if (actual != expected) {
            throw GradleException(
                "Resolved com.github.GTNewHorizons:GT5-Unofficial version $actual, expected $expected",
            )
        }
    }
}
