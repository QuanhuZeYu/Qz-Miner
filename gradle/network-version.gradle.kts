import org.gradle.api.GradleException

// FML 区间与制品同源；不能用 @NetworkCheckHandler 抢占原生区间对象。
val buildVersion = project.version.toString()
val generatedVersionRangeDir = layout.buildDirectory.dir("generated/sources/versionRange/java")

val generateVersionRange = tasks.register("generateVersionRange") {
    val versionValue = buildVersion
    val outputDir = generatedVersionRangeDir
    inputs.property("version", versionValue)
    outputs.dir(outputDir)
    doLast {
        val core = versionValue.split('.', '-', '+')
        if (core.size < 2) {
            throw GradleException("制品版本不是 major.minor.patch 形态，无法推导联机版本区间：" + versionValue)
        }
        val major = core[0].toIntOrNull()
            ?: throw GradleException("制品版本 major 段不是整数：" + versionValue)
        val minor = core[1].toIntOrNull()
            ?: throw GradleException("制品版本 minor 段不是整数：" + versionValue)
        val range = "[" + major + "." + minor + ".0-alpha," + major + "." + (minor + 1) + ".0-alpha)"
        val packageDir = outputDir.get().asFile.resolve("club/heiqi/qz_miner")
        packageDir.mkdirs()
        packageDir.resolve("NetworkVersionRange.java").writeText(
            listOf(
                "package club.heiqi.qz_miner;",
                "",
                "/**",
                " * 联机兼容的远端版本区间（由构建生成，勿手改）。",
                " *",
                " * <p>真源是制品版本 {@link Tags#VERSION}；区间形态与边界判据见 {@code NetworkVersionRangeTest}。</p>",
                " */",
                "public final class NetworkVersionRange {",
                "",
                "    /** 远端版本必须落在 {@code [X.Y.0-alpha, X.(Y+1).0-alpha)} 内。 */",
                "    public static final String VALUE = \"" + range + "\";",
                "",
                "    private NetworkVersionRange() {",
                "    }",
                "}",
                ""
            ).joinToString("\n")
        )
    }
}

extensions.getByType<org.gradle.api.tasks.SourceSetContainer>().named("main") { java.srcDir(generateVersionRange) }
