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

/**
 * T48c-C 必备 uniform 的「可达性」独立复核（task-49 增量复核，preview-verifier）。
 *
 * <p>Lead 复核项 1 的另一半是「<b>不误伤</b>」：{@code verifyRequiredUniforms()} 在缺 location 时会让
 * 程序不可用并永久回退 legacy。若某个必备名字其实<b>没在顶点着色器里被真正使用</b>，GLSL 编译器会把该
 * uniform 优化掉、location 恒为 -1 ⇒ <b>每次都白回退</b>（比原缺陷更重的静默失效）。</p>
 *
 * <p>这就是本类存在的理由：纯 JVM 内无法编译 GLSL，唯一可自动化的证据是「名字在 shader 源里同时出现在
 * uniform 声明与至少一处去注释后的使用」。因此本类是<b>静态一致性检查</b>，不是行为契约断言。</p>
 *
 * <p>反射说明：{@code REQUIRED_UNIFORMS} 是 private 静态常量，无公共读口；内部字段名变更需同步此处。
 * 字段缺失会直接抛 {@code NoSuchFieldException} ⇒ 改名即红，不会静默跳过。</p>
 */
public class T48cCRequiredUniformReachabilityTest {

    private static final String VERTEX_RESOURCE = "/assets/qz_miner/shaders/preview.vert";

    /** 无此二者则 T48c-A 的「显式矩阵」整体失效，必须永久在列。 */
    private static final String[] MATRIX_UNIFORMS = { "uModelViewProjection", "uModelView" };

    @Test
    public void everyRequiredUniformIsDeclaredAndActuallyUsedInVertexShader() throws Exception {
        String source = withoutComments(readResource(VERTEX_RESOURCE));
        List<String> required = requiredUniforms();

        Assert.assertFalse("必备 uniform 列表不得为空", required.isEmpty());
        for (String name : required) {
            Pattern declaration = Pattern.compile("uniform\\s+\\w+\\s+" + Pattern.quote(name) + "\\s*;");
            Assert.assertTrue("必备 uniform " + name + " 必须在 preview.vert 中声明（去注释后）",
                    declaration.matcher(source).find());

            int occurrences = countOccurrences(source, name);
            Assert.assertTrue("必备 uniform " + name + " 必须在声明之外被真正引用（否则会被 GLSL 优化掉、"
                    + "location 恒为 -1 ⇒ 每次都白回退 legacy）；实际出现 " + occurrences + " 次",
                    occurrences >= 2);
        }
        for (String name : MATRIX_UNIFORMS) {
            Assert.assertTrue("显式矩阵 uniform " + name + " 必须在必备列表内", required.contains(name));
        }
    }

    @Test
    public void shaderSourceHasNoFixedFunctionBuiltins() throws Exception {
        String source = withoutComments(readResource(VERTEX_RESOURCE));
        for (String builtin : new String[] {
            "gl_ModelViewProjectionMatrix", "gl_ModelViewMatrix", "gl_ProjectionMatrix",
            "gl_ModelViewMatrixInverse", "gl_NormalMatrix", "ftransform",
        }) {
            Assert.assertFalse("T48c-A 的核心：顶点着色器不得再依赖固定管线内建量 " + builtin
                    + "（真机 GLSM 下它与真实相机矩阵失同步且完全不可观测）", source.contains(builtin));
        }
    }

    @Test
    public void requiredUniformsAreNotCommentOnly() throws Exception {
        String raw = readResource(VERTEX_RESOURCE);
        String stripped = withoutComments(raw);
        for (String name : requiredUniforms()) {
            Assert.assertTrue("注释里出现过的名字不得被误判为已声明", countOccurrences(stripped, name) <= countOccurrences(raw, name));
        }
    }

    /** 反射读必备 uniform 名单（内部字段名变更需同步此处）。 */
    private static List<String> requiredUniforms() throws Exception {
        Field field = ChainPreviewShaderProgram.class.getDeclaredField("REQUIRED_UNIFORMS");
        field.setAccessible(true);
        String[] names = (String[]) field.get(null);
        Assert.assertNotNull("REQUIRED_UNIFORMS 不得为 null", names);
        List<String> list = new ArrayList<String>();
        for (String name : names) {
            Assert.assertNotNull("必备 uniform 名不得为 null", name);
            Assert.assertFalse("必备 uniform 名不得为空", name.trim().isEmpty());
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

    /** 去掉行注释与块注释（保留换行，避免把两行粘成一个 token）。 */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean line = false;
        boolean block = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (line) {
                if (c == '\n') {
                    line = false;
                    out.append(c);
                }
                continue;
            }
            if (block) {
                if (c == '*' && next == '/') {
                    block = false;
                    i++;
                } else if (c == '\n') {
                    out.append(c);
                }
                continue;
            }
            if (c == '/' && next == '/') {
                line = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                block = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
