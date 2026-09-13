
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    id("com.github.ElytraServers.elytra-conventions") version("v1.1.2")
    id("com.gtnewhorizons.gtnhconvention")
}

val buildVersion = providers.environmentVariable("VERSION").orElse("5.3.1-dev").get()
version = buildVersion
extra["modVersion"] = buildVersion

// 不再存在 gradle/gtnh-baselines.json 基线清单与 verifyGtnhBaseline 校验任务，
// CI/发布也不再传递 -Pqz.gtnh.expectedGregTechVersion / -Pelytra.manifest.version 覆盖。
//
// 兼容承诺：运行期兼容范围含 GTNH 2.9.0-beta-2 与 2.9.0-beta-3；依据是源码对 GTNH 侧组件零静态链接
// + 双基线编译实证（beta-2/beta-3 均 compileJava/compileTestJava 通过）；真机运行态未验证。
// 源码不得静态链接任一基线独有的上游 API，跨版本差异只能以源码级运行期兼容消化；
// 双基线编译实证按工作站任务笔记的人工流程执行（临时切换基线编译后按原始字节恢复并校验 sha256）。

// ---------------------------------------------------------------------------
// 离线 GLSL 校验闸门（T49）
//
// 背景：build 只编译 Java、从不编译 GLSL，因此着色器源码里的硬错误（未声明变量、类型错、
// 版本特性越界）在 CI 上是绿的、只在真机炸；2026-09-13 已真实发生过一次
// （5d2e0008 删除 pixelsPerWorldUnit 声明 -> 真机编译失败 -> 回退链把失败吞成「观感正常」）。
//
// 方案：Khronos glslang 官方二进制（参考实现级语义校验，支持 desktop GLSL 110~460，含 120）。
// 两条实测结论：① 必须用不带 -G/-V 的纯校验模式（SPIR-V 后端对 120 直接拒绝）；
// ② 16.6.0 的 Windows 包内只有 glslang.exe（12.3.0 起 glslangValidator 已改名并建符号链接）。
//
// 离线与失效通道：
//   · 工具缓存在 GRADLE_USER_HOME/caches/qz-glslang/<版本>/<平台>/，命中即完全离线；
//   · 缓存缺失才按平台下载并校验 sha256（版本号与摘要写死在下方，是唯一真源，升级即失效）；
//   · 环境变量 QZ_GLSLANG 可指向任意可用的 glslang 可执行文件，直接跳过下载（CI 预置/内网镜像用）；
//   · 下载失败、摘要不匹配、平台无预置源一律硬失败，绝不静默跳过（否则又回到 CI 假绿）。
// ---------------------------------------------------------------------------

/** glslang 版本与各平台压缩包摘要（单一真源；升级版本号即整体失效重建）。 */
val glslangVersion = "16.6.0"
val glslangUrlWindows = "https://github.com/KhronosGroup/glslang/releases/download/" + glslangVersion +
    "/glslang-" + glslangVersion + "-windows-x86_64-release.zip"
val glslangUrlLinux = "https://github.com/KhronosGroup/glslang/releases/download/" + glslangVersion +
    "/glslang-" + glslangVersion + "-linux-x86_64-release.zip"
val glslangSha256Windows = "82bf434e69b9bb4829de7e2b4bc2c5e7a7861e53d66cf75e5cc70f5f694a8d9b"
val glslangSha256Linux = "a3fc4f083b1793eb53e55fa3577ac9649ffbe0340715e30d116290fb5382393f"

val hostOsName = System.getProperty("os.name", "").lowercase()
val hostArch = System.getProperty("os.arch", "").lowercase()
val isWindowsHost = hostOsName.contains("win")
val isLinuxHost = hostOsName.contains("linux")
val isX64Host = hostArch.contains("amd64") || hostArch.contains("x86_64") || hostArch.contains("x64")
val glslangPlatformId = when {
    isWindowsHost && isX64Host -> "windows-x86_64"
    isLinuxHost && isX64Host -> "linux-x86_64"
    else -> "unsupported"
}
val glslangUrl = when (glslangPlatformId) {
    "windows-x86_64" -> glslangUrlWindows
    "linux-x86_64" -> glslangUrlLinux
    else -> ""
}
val glslangArchiveSha256 = when (glslangPlatformId) {
    "windows-x86_64" -> glslangSha256Windows
    "linux-x86_64" -> glslangSha256Linux
    else -> ""
}
val glslangExecutableName = if (isWindowsHost) "glslang.exe" else "glslang"
val glslangToolDir = gradle.gradleUserHomeDir.resolve("caches/qz-glslang/" + glslangVersion + "/" + glslangPlatformId)
val glslangToolFile = glslangToolDir.resolve("bin/" + glslangExecutableName)
val glslangOverridePath = providers.environmentVariable("QZ_GLSLANG").orElse("").get()

/** 把 glslang 可执行文件准备到缓存目录（或校验 QZ_GLSLANG 覆盖路径）。 */
abstract class EnsureGlslangTask : DefaultTask() {

    @get:Input
    abstract val toolFilePath: Property<String>

    @get:Input
    abstract val archiveUrl: Property<String>

    @get:Input
    abstract val archiveSha256: Property<String>

    @get:Input
    abstract val executableName: Property<String>

    @get:Input
    abstract val overridePath: Property<String>

    @TaskAction
    fun ensure() {
        val override = overridePath.get()
        if (override.isNotEmpty()) {
            val file = File(override)
            if (!file.isFile) {
                throw GradleException("QZ_GLSLANG 指向的文件不存在：" + override)
            }
            logger.lifecycle("glslang: 使用 QZ_GLSLANG 覆盖路径 {}（跳过下载）", file.absolutePath)
            return
        }
        val expected = archiveSha256.get()
        val target = File(toolFilePath.get())
        val stamp = File(target.parentFile, "archive.sha256")
        if (target.isFile && stamp.isFile && stamp.readText().trim() == expected) {
            logger.lifecycle("glslang: 缓存命中 {}", target.absolutePath)
            return
        }
        val url = archiveUrl.get()
        if (url.isEmpty()) {
            throw GradleException(
                "当前平台（" + System.getProperty("os.name") + "/" + System.getProperty("os.arch") +
                    "）没有预置 glslang 下载源；请用环境变量 QZ_GLSLANG 指向可用的 glslang 可执行文件。"
            )
        }
        target.parentFile.mkdirs()
        val archive = File(target.parentFile, "glslang-download.zip")
        logger.lifecycle("glslang: 缓存未命中，下载 {}", url)
        download(url, archive)
        val actual = sha256(archive)
        if (!actual.equals(expected, ignoreCase = true)) {
            archive.delete()
            throw GradleException("glslang 压缩包 sha256 不匹配：期望 " + expected + "，实际 " + actual)
        }
        extract(archive, target, executableName.get())
        archive.delete()
        if (!target.isFile) {
            throw GradleException("glslang 解压失败（包内未见 bin/" + executableName.get() + "）：" + target.absolutePath)
        }
        target.setExecutable(true)
        stamp.writeText(expected)
        logger.lifecycle("glslang: 就绪 {}", target.absolutePath)
    }

    private fun download(url: String, target: File) {
        val connection = URI(url).toURL().openConnection()
        try {
            if (connection is HttpURLConnection) {
                connection.connectTimeout = 30_000
                connection.readTimeout = 180_000
            }
            connection.getInputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            if (connection is HttpURLConnection) {
                connection.disconnect()
            }
        }
    }

    private fun extract(archive: File, target: File, executableName: String) {
        val wanted = "bin/" + executableName
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                if (name.endsWith(wanted) && !entry.isDirectory) {
                    target.parentFile.mkdirs()
                    target.outputStream().use { output -> zip.copyTo(output) }
                    break
                }
                zip.closeEntry()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val text = StringBuilder()
        for (byte in digest.digest()) {
            text.append(String.format("%02x", byte))
        }
        return text.toString()
    }
}

/** 逐文件离线编译校验：只看 glslang 退出码（0 成功 / 2 编译错 / 3 链接错）。 */
abstract class ValidateShadersTask : DefaultTask() {

    @get:InputDirectory
    abstract val shaderDir: DirectoryProperty

    @get:Input
    abstract val toolPath: Property<String>

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun validate() {
        val directory = shaderDir.get().asFile
        val executable = File(toolPath.get())
        if (!executable.isFile) {
            throw GradleException(
                "glslang 可执行文件不存在：" + executable.absolutePath + "（先跑 ensureGlslang，或用 QZ_GLSLANG 指定）"
            )
        }
        val shaders = directory.walkTopDown()
            .filter { it.isFile && (it.extension == "vert" || it.extension == "frag" || it.extension == "geom") }
            .sortedBy { it.path }
            .toList()
        check(shaders.isNotEmpty()) { "着色器目录下没有 .vert/.frag/.geom 文件：" + directory.absolutePath }
        val failures = ArrayList<String>()
        for (shader in shaders) {
            val relative = shader.relativeTo(directory).path
            // 版本声明检查：glslang 对没有 #version 的文件按 ES 100 宽松解析，会掩盖 desktop 语义错误。
            val firstLine = shader.readLines().firstOrNull { it.trim().isNotEmpty() }?.trim() ?: ""
            if (!Regex("^#version\\s+1[0-9][0-9]").containsMatchIn(firstLine)) {
                failures.add(relative + "：首个非空行必须是 desktop 版本声明（#version 1xx），实际为「" + firstLine + "」")
                continue
            }
            val stage = when (shader.extension) {
                "vert" -> "vert"
                "frag" -> "frag"
                else -> "geom"
            }
            val output = ByteArrayOutputStream()
            val exitCode = execOps.exec {
                commandLine(executable.absolutePath, "--enhanced-msgs", "-S", stage, shader.absolutePath)
                isIgnoreExitValue = true
                standardOutput = output
                errorOutput = output
            }.exitValue
            if (exitCode != 0) {
                failures.add(relative + "：glslang rc=" + exitCode + "\n" + output.toString("UTF-8").trim())
            } else {
                logger.lifecycle("GLSL ok   [{}]", relative)
            }
        }
        if (failures.isNotEmpty()) {
            throw GradleException("GLSL 离线校验失败（" + failures.size + " 个文件）：\n" + failures.joinToString("\n"))
        }
    }
}

val ensureGlslang = tasks.register<EnsureGlslangTask>("ensureGlslang") {
    group = "verification"
    description = "准备离线 GLSL 校验工具 glslang（缓存优先，缺失才下载并校验 sha256）"
    toolFilePath.set(glslangToolFile.absolutePath)
    archiveUrl.set(glslangUrl)
    archiveSha256.set(glslangArchiveSha256)
    executableName.set(glslangExecutableName)
    overridePath.set(glslangOverridePath)
}

val validateShaders = tasks.register<ValidateShadersTask>("validateShaders") {
    group = "verification"
    description = "用 glslang 离线编译校验全部着色器源码（硬失败，不静默跳过）"
    dependsOn(ensureGlslang)
    shaderDir.set(layout.projectDirectory.dir("src/main/resources/assets/qz_miner/shaders"))
    toolPath.set(if (glslangOverridePath.isEmpty()) glslangToolFile.absolutePath else glslangOverridePath)
}

// 挂到既有 lifecycle：check（build 会经过 check）与 test 都先过着色器闸门。
tasks.matching { it.name == "check" || it.name == "test" }.configureEach {
    dependsOn(validateShaders)
}
