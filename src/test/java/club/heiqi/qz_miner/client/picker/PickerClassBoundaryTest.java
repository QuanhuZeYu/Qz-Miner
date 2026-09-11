package club.heiqi.qz_miner.client.picker;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.CommonProxy;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;

/**
 * 类边界守卫：common 字节码不得反向引用 client picker / GuiScreen / LWJGL（原 5 类清扫面），
 * 且新候选源族（数据面）不得携带客户端/GL 依赖（设计 N14）。
 */
public class PickerClassBoundaryTest {

    private static final String PICKER_PACKAGE = "club/heiqi/qz_miner/client/picker";

    /** {@code @SideOnly} 注解的类型描述符：带该注解的类按声明只在单侧加载，不属 common 面。 */
    private static final String SIDE_ONLY_ANNOTATION = "Lcpw/mods/fml/relauncher/SideOnly;";

    @Test
    public void commonBytecodeHasNoClientPickerOrGraphicsReferences() throws Exception {
        assertClean(QzMinerConfigSchema.class);
        assertClean(ConfigBootstrap.class);
        assertClean(CommonProxy.class);
        assertClean(MyMod.class);
        assertClean(ObjectGroupParser.class);
    }

    /** N14：候选源族必须平台中立（无 net/minecraft/client、无 LWJGL、无 ConfigUI 引用）。 */
    @Test
    public void pickerDataSourceTypesStayFreeOfClientAndGraphicsReferences() throws Exception {
        assertPlatformNeutral(BlockPickerCandidateSource.class);
        assertPlatformNeutral(BlockRegistrySnapshot.class);
        assertPlatformNeutral(BlockVariantShardCache.class);
        assertPlatformNeutral(BlockVariantMaterializer.class);
        assertPlatformNeutral(BlockPickerNameIndex.class);
    }

    /** N14：client/ 之外的**全部**已编译 common 类都不得引用 client picker（不限于固定 5 类）。 */
    @Test
    public void pickerTypesStayOutOfCommonBytecode() throws Exception {
        File packageRoot = new File(classesRoot(), "club/heiqi/qz_miner");
        Assert.assertTrue("必须能找到已编译 common 类目录: " + packageRoot.getPath(), packageRoot.isDirectory());
        List<String> scanned = new ArrayList<String>();
        List<String> offenders = new ArrayList<String>();
        collectCommonClasses(packageRoot, packageRoot, sidedProxyClientClass(), scanned, offenders);
        Assert.assertTrue("扫描面必须覆盖 common 全包（>50 类），实际 " + scanned.size(), scanned.size() > 50);
        Assert.assertEquals("common 字节码禁止引用 client picker", Collections.<String>emptyList(), offenders);
    }

    /**
     * 客户端侧 {@code @SidedProxy} 类：它在根包下（不在 {@code client/} 段内），但按声明只在客户端加载，
     * 因此从「common 字节码」扫描面中排除——排除项直接读 {@code MyMod} 的 {@code @SidedProxy} 声明，
     * 不写死类名（声明变化时守卫跟随）。
     */
    private static String sidedProxyClientClass() throws IOException {
        File source = new File("src/main/java/club/heiqi/qz_miner/MyMod.java");
        Assert.assertTrue("MyMod 源码必须存在: " + source.getPath(), source.isFile());
        String text = new String(Files.readAllBytes(Paths.get(source.getPath())), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("clientSide\\s*=\\s*\"([^\"]+)\"").matcher(text);
        Assert.assertTrue("MyMod 必须声明 @SidedProxy clientSide", matcher.find());
        String qualified = matcher.group(1);
        return qualified.substring(qualified.lastIndexOf('.') + 1) + ".class";
    }

    private static void collectCommonClasses(File root, File directory, String clientProxyClass,
            List<String> scanned, List<String> offenders) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectCommonClasses(root, child, clientProxyClass, scanned, offenders);
                continue;
            }
            if (!child.getName().endsWith(".class")) {
                continue;
            }
            String relative = root.toURI().relativize(child.toURI()).getPath();
            boolean clientProxy = child.getParentFile().equals(root) && child.getName().equals(clientProxyClass);
            if (relative.startsWith("client/") || relative.contains("/client/") || clientProxy) {
                continue;
            }
            String content = readBytes(child);
            if (content.contains(SIDE_ONLY_ANNOTATION)) {
                continue;
            }
            scanned.add(relative);
            if (content.contains(PICKER_PACKAGE)) {
                offenders.add(relative);
            }
        }
    }

    private static File classesRoot() throws URISyntaxException {
        return new File(MyMod.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static void assertClean(Class<?> type) throws Exception {
        String content = readBytes(new File(classesRoot(), type.getName().replace('.', '/') + ".class"));
        Assert.assertFalse(content.contains(PICKER_PACKAGE));
        assertPlatformNeutralContent(type, content);
    }

    private static void assertPlatformNeutral(Class<?> type) throws Exception {
        assertPlatformNeutralContent(type, readBytes(
                new File(classesRoot(), type.getName().replace('.', '/') + ".class")));
    }

    private static void assertPlatformNeutralContent(Class<?> type, String content) {
        Assert.assertFalse(type.getName() + " must not reference client classes",
                content.contains("net/minecraft/client/"));
        Assert.assertFalse(type.getName() + " must not reference ConfigUI", content.contains("ConfigUI"));
        Assert.assertFalse(type.getName() + " must not reference LWJGL", content.contains("org/lwjgl/"));
    }

    private static String readBytes(File file) throws IOException {
        Assert.assertTrue("字节码必须存在: " + file.getPath(), file.isFile());
        InputStream in = new java.io.FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int size;
            while ((size = in.read(buffer)) >= 0) {
                output.write(buffer, 0, size);
            }
            return new String(output.toByteArray(), "ISO-8859-1");
        } finally {
            in.close();
        }
    }
}
