package club.heiqi.qz_miner.chain.client.verify;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.render.ChainPreviewShaderProgram;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 反射 uniform 清单并核对声明与词边界引用，硬必备项需在显式常量死块之外可见。
 * 能力项允许只在死块引用，两类清单保持互斥；词法检查覆盖当前源码结构，真实优化和链接由 GL 编译确认。
 * 注释剥离、词边界及花括号切片复用 JavaSourceSlices，GLSL 声明沿本类现有解析器处理。
 */
public class T48cCRequiredUniformReachabilityTest {

    private static final String VERTEX_RESOURCE = "/assets/qz_miner/shaders/preview.vert";

    /** 编译期常量门控的死分支锚点（GLSL 规范允许编译器把其中的 uniform 引用整体优化掉）。 */
    private static final String DEAD_ANCHOR = "if (false";

    /** 无此二者则 T48c-A 的「显式矩阵」整体失效；必须落在「硬必备 ∪ 能力型」内。 */
    private static final String[] MATRIX_UNIFORMS = { "uModelViewProjection", "uModelView" };

    @Test
    public void everyRequiredUniformIsDeclaredAndActuallyUsedInVertexShader() throws Exception {
        String source = JavaSourceSlices.stripComments(readResource(VERTEX_RESOURCE));
        List<int[]> dead = deadRanges(source);
        List<String> required = requiredUniforms();
        List<String> capability = capabilityUniforms();

        Assert.assertFalse("硬必备 uniform 列表不得为空", required.isEmpty());
        for (String name : required) {
            Assert.assertTrue("硬必备 uniform " + name + " 必须在 preview.vert 中声明（去注释后）",
                    declarationPattern(name).matcher(source).find());
            int live = countLiveOccurrences(source, name, dead);
            Assert.assertTrue("硬必备 uniform " + name + " 必须在 if (false && …) 死块之外被真正引用"
                    + "（否则会被 GLSL 优化掉、location 恒为 -1 ⇒ 整个着色器后端不可用）；实际存活引用 "
                    + live + " 次。若它属于「按契约保留但当前关闭」的能力，应登记到 CAPABILITY_UNIFORMS。",
                    live >= 1);
        }
        for (String name : capability) {
            Assert.assertTrue("能力型 uniform " + name + " 必须在 preview.vert 中声明（去注释后）",
                    declarationPattern(name).matcher(source).find());
            Assert.assertFalse("能力型 uniform 不得同时登记为硬必备：" + name, required.contains(name));
        }
        for (String name : MATRIX_UNIFORMS) {
            Assert.assertTrue("显式矩阵 uniform " + name + " 必须在硬必备或能力型清单内",
                    required.contains(name) || capability.contains(name));
        }
    }

    /** 只被死块引用的 uniform 必须全部登记为能力型（清单漂移 = 红灯）。 */
    @Test
    public void uniformsReferencedOnlyFromDeadCodeAreRegisteredAsCapability() throws Exception {
        String source = JavaSourceSlices.stripComments(readResource(VERTEX_RESOURCE));
        List<int[]> dead = deadRanges(source);
        List<String> onlyDead = new ArrayList<String>();
        for (String name : declaredUniforms(source)) {
            if (countLiveOccurrences(source, name, dead) == 0) {
                onlyDead.add(name);
            }
        }
        List<String> capability = capabilityUniforms();
        List<String> unregistered = new ArrayList<String>();
        for (String name : onlyDead) {
            if (!capability.contains(name)) {
                unregistered.add(name);
            }
        }
        Assert.assertTrue("只被 if (false && …) 死块引用的 uniform（" + onlyDead
                + "）必须登记为能力型，否则真机必然「白回退 / 程序不可用」；未登记：" + unregistered,
                unregistered.isEmpty());
    }

    /**
     * 注册清单里的名字必须在<strong>去注释源码</strong>里真的出现。
     *
     * <p>旧断言是 {@code count(去注释) <= count(原文)}——对任何实现都成立的自洽式空壳（去注释只会
     * 删字符），永远不可能红。现在判「清单漂移」：名字只活在注释里、或已从着色器删除却仍留在
     * {@code REQUIRED_UNIFORMS / CAPABILITY_UNIFORMS} 里，都意味着运行期 {@code verifyRequiredUniforms}
     * 恒失败 ⇒ 后端每次白回退。计数走词边界口径，避免长名冒充短名。</p>
     */
    @Test
    public void requiredUniformsAreNotCommentOnly() throws Exception {
        String stripped = JavaSourceSlices.stripComments(readResource(VERTEX_RESOURCE));
        List<String> registered = new ArrayList<String>(requiredUniforms());
        registered.addAll(capabilityUniforms());
        Assert.assertFalse("注册清单不得为空", registered.isEmpty());
        for (String name : registered) {
            Assert.assertTrue("uniform " + name + " 在去注释源码里一次也没出现（清单已漂移："
                    + "只留在注释里，或已从着色器删除）",
                    JavaSourceSlices.wordCount(stripped, name) >= 1);
        }
    }

    // ---------------------------------------------------------------- 内部

    private static Pattern declarationPattern(String name) {
        return Pattern.compile("uniform\\s+\\w+\\s+" + Pattern.quote(name) + "\\s*;");
    }

    /** 提取全部 uniform 声明名（去注释后的源码）。 */
    private static List<String> declaredUniforms(String source) {
        List<String> names = new ArrayList<String>();
        Matcher matcher = Pattern.compile("uniform\\s+\\w+\\s+(\\w+)\\s*;").matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * {@code if (false && …) { … }} 的字符区间。
     *
     * <p>这些分支被编译期常量门控，GLSL 编译器对其中的 uniform 引用视作未使用（规范允许优化掉），
     * 因此必须从「可达性」里剔除。</p>
     */
    private static List<int[]> deadRanges(String source) {
        List<int[]> ranges = new ArrayList<int[]>();
        int from = 0;
        while (true) {
            int anchor = JavaSourceSlices.wordIndexOf(source, DEAD_ANCHOR, from);
            if (anchor < 0) {
                return ranges;
            }
            String tail = source.substring(anchor);
            int brace = tail.indexOf('{');
            int semicolon = tail.indexOf(';');
            if (brace < 0 || (semicolon >= 0 && semicolon < brace)) {
                // 无花括号体的死语句：截不出块区间，按「不剔除」保守处理
                from = anchor + DEAD_ANCHOR.length();
                continue;
            }
            String block = JavaSourceSlices.block(tail, DEAD_ANCHOR, "死块");
            ranges.add(new int[] { anchor, anchor + block.length() - 1 });
            from = anchor + block.length();
        }
    }

    /**
     * 统计「声明行之外、且不在死块内」的引用次数（这才是编译器眼中真正用到的引用）。
     *
     * <p>按<strong>词边界</strong>统计：裸子串会把 {@code uModelView} 的引用算进
     * {@code uModelViewProjection} 的出现里，而这两个名字正是同一份清单里的邻居——
     * 短名只剩声明、长名还在用时，旧口径会照样绿。</p>
     */
    private static int countLiveOccurrences(String source, String name, List<int[]> deadRanges) {
        int live = 0;
        int index = JavaSourceSlices.wordIndexOf(source, name);
        while (index >= 0) {
            if (!isDeclarationLine(source, index) && !inDeadRange(deadRanges, index)) {
                live++;
            }
            index = JavaSourceSlices.wordIndexOf(source, name, index + name.length());
        }
        return live;
    }

    private static boolean isDeclarationLine(String source, int index) {
        int lineStart = source.lastIndexOf('\n', index) + 1;
        int lineEnd = source.indexOf('\n', index);
        String line = source.substring(lineStart, lineEnd < 0 ? source.length() : lineEnd);
        return line.trim().startsWith("uniform");
    }

    private static boolean inDeadRange(List<int[]> deadRanges, int index) {
        for (int[] range : deadRanges) {
            if (index >= range[0] && index <= range[1]) {
                return true;
            }
        }
        return false;
    }

    /** 反射读硬必备名单（内部字段名变更需同步此处）。 */
    private static List<String> requiredUniforms() throws Exception {
        return readUniformList("REQUIRED_UNIFORMS", "硬必备");
    }

    /** 反射读能力型名单（内部字段名变更需同步此处）。 */
    private static List<String> capabilityUniforms() throws Exception {
        return readUniformList("CAPABILITY_UNIFORMS", "能力型");
    }

    private static List<String> readUniformList(String fieldName, String label) throws Exception {
        Field field = ChainPreviewShaderProgram.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        String[] names = (String[]) field.get(null);
        Assert.assertNotNull(label + " uniform 清单不得为 null：" + fieldName, names);
        List<String> list = new ArrayList<String>();
        for (String name : names) {
            Assert.assertNotNull(label + " uniform 名不得为 null", name);
            Assert.assertFalse(label + " uniform 名不得为空", name.trim().isEmpty());
            list.add(name);
        }
        return list;
    }

    private static String readResource(String path) throws Exception {
        InputStream stream = ChainPreviewShaderProgram.class.getResourceAsStream(path);
        Assert.assertNotNull("资源必须存在：" + path, stream);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            stream.close();
        }
    }

}
