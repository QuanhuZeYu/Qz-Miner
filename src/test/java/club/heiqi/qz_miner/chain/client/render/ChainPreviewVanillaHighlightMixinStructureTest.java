package club.heiqi.qz_miner.chain.client.render;

import java.io.InputStream;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.Type;
import org.spongepowered.asm.lib.tree.AnnotationNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.MethodNode;

/**
 * T34 / B2.5 注入点结构断言：直接读编译产物（class 资源）的注解元数据，断言
 * 「目标类 = RenderGlobal、注入点 = drawSelectionBox(EntityPlayer, MovingObjectPosition, int, float)
 * 的 HEAD、cancellable = true、handler 以 CallbackInfo 收尾、remap 未显式关闭」。
 *
 * <p>不做源码字符串断言：断言对象是 javac + Mixin AP 后的字节码元数据，
 * 结构漂移（改签名 / 换注入点 / 关 remap）会直接失败。</p>
 */
public class ChainPreviewVanillaHighlightMixinStructureTest {

    private static final String MIXIN_RESOURCE =
        "club/heiqi/qz_miner/mixins/client/MixinRenderGlobalVanillaHighlight.class";
    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String SELECTION_BOX_DESCRIPTOR =
        "drawSelectionBox(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/util/MovingObjectPosition;IF)V";

    @Test
    public void mixinTargetsRenderGlobalWithRefmapRemapEnabled() throws Exception {
        ClassNode node = readMixinClass();
        AnnotationNode mixin = findAnnotation(node.visibleAnnotations, node.invisibleAnnotations, MIXIN_ANNOTATION);
        Assert.assertNotNull("@Mixin 注解缺失，注入点不会生效", mixin);
        Assert.assertEquals(
            "目标类必须是 RenderGlobal（原版黑色选择框唯一入口）",
            "Lnet/minecraft/client/renderer/RenderGlobal;",
            firstClassValue(mixin, "value"));

        Object remap = valueOf(mixin, "remap");
        Assert.assertTrue(
            "remap 不得显式关闭：关闭后 refmap 不生成，生产环境 selector 无法映射到 SRG",
            remap == null || Boolean.TRUE.equals(remap));
    }

    @Test
    public void injectionIsExactlyOneHeadCancellableOnSelectionBoxDescriptor() throws Exception {
        ClassNode node = readMixinClass();
        MethodNode handler = null;
        AnnotationNode inject = null;
        int injectCount = 0;
        for (MethodNode method : node.methods) {
            AnnotationNode found = findAnnotation(
                method.visibleAnnotations, method.invisibleAnnotations, INJECT_ANNOTATION);
            if (found == null) {
                continue;
            }
            injectCount++;
            handler = method;
            inject = found;
        }
        Assert.assertEquals("只允许一个注入点（最小侵入）", 1, injectCount);
        Assert.assertNotNull(handler);

        Assert.assertEquals("注入点必须是唯一的选择框方法（含精确描述符）",
            SELECTION_BOX_DESCRIPTOR, firstStringValue(inject, "method"));
        Assert.assertEquals("必须可取消，否则无法抑制原版框", Boolean.TRUE, valueOf(inject, "cancellable"));

        AnnotationNode at = firstAnnotationValue(inject, "at");
        Assert.assertNotNull("@At 缺失", at);
        Assert.assertEquals("必须注入 HEAD（idx != 0 / 非方块命中直接 return，不做全局取消）",
            "HEAD", firstStringValue(at, "value"));
        Assert.assertEquals(AT_ANNOTATION, at.desc);

        Assert.assertTrue("handler 必须以 CallbackInfo 收尾：" + handler.desc,
            handler.desc.endsWith("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"));
        Assert.assertTrue("handler 必须为 private 且非 static",
            (handler.access & org.spongepowered.asm.lib.Opcodes.ACC_PRIVATE) != 0
                && (handler.access & org.spongepowered.asm.lib.Opcodes.ACC_STATIC) == 0);
    }

    private static ClassNode readMixinClass() throws Exception {
        InputStream stream = ChainPreviewVanillaHighlightMixinStructureTest.class
            .getClassLoader()
            .getResourceAsStream(MIXIN_RESOURCE);
        Assert.assertNotNull("编译产物缺失：" + MIXIN_RESOURCE, stream);
        try {
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        } finally {
            stream.close();
        }
    }

    private static AnnotationNode findAnnotation(
            List<AnnotationNode> visible, List<AnnotationNode> invisible, String descriptor) {
        AnnotationNode found = findIn(visible, descriptor);
        return found != null ? found : findIn(invisible, descriptor);
    }

    private static AnnotationNode findIn(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (descriptor.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    private static Object valueOf(AnnotationNode annotation, String key) {
        if (annotation.values == null) {
            return null;
        }
        for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
            if (key.equals(annotation.values.get(index))) {
                return annotation.values.get(index + 1);
            }
        }
        return null;
    }

    /** 注解数组值（如 @Inject.at）取首个元素；单值直接返回。 */
    private static AnnotationNode firstAnnotationValue(AnnotationNode annotation, String key) {
        Object value = valueOf(annotation, key);
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            return values.isEmpty() || !(values.get(0) instanceof AnnotationNode)
                ? null
                : (AnnotationNode) values.get(0);
        }
        return value instanceof AnnotationNode ? (AnnotationNode) value : null;
    }

    /** 注解数组值（如 method / at.value）取首个元素；单值直接返回。 */
    private static String firstStringValue(AnnotationNode annotation, String key) {
        Object value = valueOf(annotation, key);
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            return values.isEmpty() ? null : String.valueOf(values.get(0));
        }
        return value instanceof String ? (String) value : null;
    }

    private static String firstClassValue(AnnotationNode annotation, String key) {
        Object value = valueOf(annotation, key);
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            return values.isEmpty() || !(values.get(0) instanceof Type) ? null : ((Type) values.get(0)).getDescriptor();
        }
        return value instanceof Type ? ((Type) value).getDescriptor() : null;
    }
}
