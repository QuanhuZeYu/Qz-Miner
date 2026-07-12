package club.heiqi.qz_miner.mixins;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;
import org.spongepowered.asm.lib.ClassWriter;
import org.spongepowered.asm.lib.MethodVisitor;
import org.spongepowered.asm.lib.Opcodes;

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
