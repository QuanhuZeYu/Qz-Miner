
plugins {
    id("com.github.ElytraServers.elytra-conventions") version("v1.1.2")
    id("com.gtnewhorizons.gtnhconvention")
}

// 从 gradle.properties 读取额外 JVM 参数并应用到运行任务
val extraJvmArgs = project.findProperty("extraRunJvmArgs") as String?
if (!extraJvmArgs.isNullOrEmpty()) {
    tasks.withType<JavaExec> {
        jvmArgs(extraJvmArgs.split(' '))
    }
}
