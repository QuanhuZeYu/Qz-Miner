package club.heiqi.qz_miner;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;

import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import club.heiqi.uilib.ui.config.ConfigDocument;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.versioning.DefaultArtifactVersion;
import cpw.mods.fml.common.versioning.VersionRange;

/** 检查实际加载的 Core 与宿主，防止文件名或构建声明正确而交付字节版本错误。 */
public class QzUiLibArtifactContractTest {

    @Test
    public void resolvedArtifactsMatchVersionAndRuntimeContract() throws Exception {
        String expected = System.getProperty("qz.uilib.version");
        Assert.assertNotNull("Gradle 必须传入已解析的 UILib 版本", expected);
        File core = artifactOf(ConfigDocument.class);
        Assert.assertTrue("Core 必须消费同版 Java8 Maven 产物: " + core,
                core.getName().equals("core-" + expected + "-java8.jar"));
        try (InputStream input = ConfigDocument.class.getResourceAsStream("ConfigDocument.class")) {
            Assert.assertNotNull(input);
            byte[] header = new byte[8];
            for (int i = 0; i < header.length; i++) {
                int value = input.read();
                Assert.assertTrue("必须是完整的 class 文件", value >= 0);
                header[i] = (byte) value;
            }
            Assert.assertEquals("Core 必须能在 Java8 加载", 52,
                    ((header[6] & 255) << 8) | (header[7] & 255));
        }

        Class<?> host = Class.forName("club.heiqi.uilib.UILib", false, getClass().getClassLoader());
        Mod declaration = host.getAnnotation(Mod.class);
        Assert.assertNotNull("宿主必须具有 Forge 声明", declaration);
        try (JarFile jar = new JarFile(artifactOf(host))) {
            try (InputStream input = jar.getInputStream(jar.getJarEntry("mcmod.info"));
                    InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonObject metadata = new JsonParser().parse(reader).getAsJsonObject()
                        .getAsJsonArray("modList").get(0).getAsJsonObject();
                String version = metadata.get("version").getAsString();
                Assert.assertEquals("Core 与宿主必须使用同一版本", expected, version);
                Assert.assertTrue("Miner 声明必须接受当前宿主", accepts(version, minerDependencyRange()));
                Assert.assertTrue("宿主必须接受自身版本",
                        declaration.acceptableRemoteVersions().isEmpty()
                                || accepts(version, declaration.acceptableRemoteVersions()));
            }
        }
    }

    private static File artifactOf(Class<?> type) throws Exception {
        File file = new File(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        Assert.assertTrue("必须加载已发布 Jar: " + file, file.isFile());
        return file;
    }

    private static String minerDependencyRange() {
        String dependencies = MyMod.class.getAnnotation(Mod.class).dependencies();
        String prefix = "required-after:qz_uilib@";
        for (String dependency : dependencies.split(";")) {
            if (dependency.startsWith(prefix)) return dependency.substring(prefix.length());
        }
        throw new AssertionError("Miner 必须声明 UILib 运行依赖");
    }

    private static boolean accepts(String version, String range) throws Exception {
        return VersionRange.createFromVersionSpec(range)
                .containsVersion(new DefaultArtifactVersion("qz_uilib", version));
    }
}
