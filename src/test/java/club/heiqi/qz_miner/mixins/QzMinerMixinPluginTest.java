package club.heiqi.qz_miner.mixins;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;
import org.spongepowered.asm.lib.ClassWriter;
import org.spongepowered.asm.lib.MethodVisitor;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.Label;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.MethodNode;

/** Mixin 代际能力选择测试。 */
public class QzMinerMixinPluginTest {

    /** 类存在且 descriptor 精确匹配时才启用能力。 */
    @Test
    public void requiresExactMethodDescriptor() throws Exception {
        byte[] bytes = classBytes(Fixture.class);
        assertTrue(QzMinerMixinPlugin.hasMethod(
            QzMinerMixinPlugin.capability(Fixture.class.getName(), "drops", "(I)Ljava/util/ArrayList;"), ignored -> bytes));
        assertFalse(QzMinerMixinPlugin.hasMethod(
            QzMinerMixinPlugin.capability(Fixture.class.getName(), "drops", "(Z)Ljava/util/ArrayList;"), ignored -> bytes));
    }

    /** 目标类仍存在但 legacy 方法已移除时不得误启 legacy mixin。 */
    @Test
    public void existingClassWithoutLegacyMethodDoesNotEnableLegacyMixin() throws Exception {
        byte[] bytes = classBytes(Fixture.class);
        assertFalse(QzMinerMixinPlugin.hasMethod(
            QzMinerMixinPlugin.capability(Fixture.class.getName(), "getDrops", "(Lnet/minecraft/block/Block;I)Ljava/util/ArrayList;"), ignored -> bytes));
    }

    /** 2.8 方法必须实际读取指定 natural 字段，2.9 同名残留类不得误启。 */
    @Test
    public void legacyCapabilityRequiresExactNaturalFieldRead() {
        QzMinerMixinPlugin.TargetCapability capability = QzMinerMixinPlugin.capability(
            "legacy.Ore", "getDrops", "(I)Ljava/util/ArrayList;", "legacy/Ore", "mNatural", "Z");
        assertTrue(QzMinerMixinPlugin.hasMethod(capability, ignored -> legacyFixture(true, "mNatural", "Z")));
        assertFalse(QzMinerMixinPlugin.hasMethod(capability, ignored -> legacyFixture(false, "mNatural", "Z")));
        assertFalse(QzMinerMixinPlugin.hasMethod(capability, ignored -> legacyFixture(true, "isNatural", "Z")));
        assertFalse(QzMinerMixinPlugin.hasMethod(capability, ignored -> legacyFixture(true, "mNatural", "I")));
    }

    /** 2.8 真实 clamp 可启用，同 descriptor 的 2.9 委托壳或无表达式方法不可启用。 */
    @Test
    public void fortuneCapabilityRequiresClampOnExactLocal() {
        QzMinerMixinPlugin.TargetCapability capability = QzMinerMixinPlugin.capability(
            "legacy.Ore", "getDrops", "(I)Ljava/util/ArrayList;", null, null, null, 1);
        assertTrue(QzMinerMixinPlugin.hasMethod(capability, ignored -> fortuneFixture(true, true, 1)));
        assertFalse(QzMinerMixinPlugin.hasMethod(capability, ignored -> fortuneFixture(true, false, 1)));
        assertFalse(QzMinerMixinPlugin.hasMethod(capability, ignored -> fortuneFixture(false, false, 1)));
        assertFalse(QzMinerMixinPlugin.hasMethod(capability, ignored -> fortuneFixture(true, true, 2)));
    }

    /** Adapter 能力必须同时具备精确 natural 字段读取与 fortune clamp。 */
    @Test
    public void adapterCapabilityRequiresFieldAndClamp() {
        QzMinerMixinPlugin.TargetCapability capability = QzMinerMixinPlugin.capability(
            "adapter.Ore", "getBigOreDrops", "(I)Ljava/util/ArrayList;",
            "adapter/Ore", "isNatural", "Z", 1);
        assertTrue(QzMinerMixinPlugin.hasMethod(capability, ignored -> adapterFixture(true, true)));
        assertFalse(QzMinerMixinPlugin.hasMethod(capability, ignored -> adapterFixture(false, true)));
        assertFalse(QzMinerMixinPlugin.hasMethod(capability, ignored -> adapterFixture(true, false)));
    }

    /** 2.9 FIELD 修改 handler 必须消费并返回 boolean，避免 Object redirect 描述符漂移。 */
    @Test
    public void modernNaturalFieldHandlersUseBooleanDescriptor() throws Exception {
        assertMethodDescriptor(MixinGTOreAdapter.class, "qzMiner$allowPlacedOreFortune", "(Z)Z");
        assertMethodDescriptor(MixinBWOreAdapter.class, "qzMiner$allowPlacedOreFortune", "(Z)Z");
    }

    private static byte[] fortuneFixture(boolean includeMethod, boolean includeClamp, int local) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "legacy/Ore", null, "java/lang/Object", null);
        if (includeMethod) addFortuneMethod(writer, "getDrops", "(I)Ljava/util/ArrayList;", includeClamp, local);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] adapterFixture(boolean includeField, boolean includeClamp) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "adapter/Ore", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "isNatural", "Z", null, null).visitEnd();
        MethodVisitor drops = writer.visitMethod(Opcodes.ACC_PUBLIC, "getBigOreDrops", "(I)Ljava/util/ArrayList;", null, null);
        drops.visitCode();
        if (includeField) {
            drops.visitVarInsn(Opcodes.ALOAD, 0);
            drops.visitFieldInsn(Opcodes.GETFIELD, "adapter/Ore", "isNatural", "Z");
            drops.visitInsn(Opcodes.POP);
        }
        addFortuneBody(drops, includeClamp, 1);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addFortuneMethod(ClassWriter writer, String name, String descriptor,
                                         boolean includeClamp, int local) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        addFortuneBody(method, includeClamp, local);
    }

    private static void addFortuneBody(MethodVisitor method, boolean includeClamp, int local) {
        if (includeClamp) {
            Label withinCap = new Label();
            method.visitVarInsn(Opcodes.ILOAD, local);
            method.visitInsn(Opcodes.ICONST_3);
            method.visitJumpInsn(Opcodes.IF_ICMPLE, withinCap);
            method.visitInsn(Opcodes.ICONST_3);
            method.visitVarInsn(Opcodes.ISTORE, local);
            method.visitLabel(withinCap);
        }
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(2, Math.max(2, local + 1));
        method.visitEnd();
    }

    private static void assertMethodDescriptor(Class<?> type, String methodName, String descriptor) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes(type)).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (methodName.equals(method.name) && descriptor.equals(method.desc)) return;
        }
        throw new AssertionError(type.getName() + " lacks " + methodName + descriptor);
    }

    private static byte[] legacyFixture(boolean includeMethod, String fieldName, String fieldDescriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "legacy/Ore", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, fieldName, fieldDescriptor, null, null).visitEnd();
        if (includeMethod) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "getDrops", "(I)Ljava/util/ArrayList;", null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, "legacy/Ore", fieldName, fieldDescriptor);
            method.visitInsn(Opcodes.POP);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ARETURN);
            method.visitMaxs(1, 2);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IOException(resource);
            byte[] buffer = new byte[4096];
            int length = 0;
            int read;
            while ((read = input.read(buffer, length, buffer.length - length)) >= 0) {
                length += read;
                if (length == buffer.length) buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2);
            }
            return java.util.Arrays.copyOf(buffer, length);
        }
    }

    private static final class Fixture {
        @SuppressWarnings("unused")
        private java.util.ArrayList<Object> drops(int fortune) { return new java.util.ArrayList<Object>(); }
    }
}
