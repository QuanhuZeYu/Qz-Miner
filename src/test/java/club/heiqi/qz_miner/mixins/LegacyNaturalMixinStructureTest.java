package club.heiqi.qz_miner.mixins;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Legacy natural 字段注入的编译字节码结构门禁。 */
public class LegacyNaturalMixinStructureTest {

    /** GT mixin 仅以注解字符串描述精确字段，不产生第三方类型 Class 常量。 */
    @Test
    public void gtMixinContainsExactFieldTargetWithoutLinkedClassConstant() throws Exception {
        assertFieldTarget(MixinTileEntityOresLegacy.class,
            "Lgregtech/common/blocks/TileEntityOres;mNatural:Z", "gregtech/common/blocks/TileEntityOres");
    }

    /** BW mixin 仅以注解字符串描述精确字段，不产生第三方类型 Class 常量。 */
    @Test
    public void bwMixinContainsExactFieldTargetWithoutLinkedClassConstant() throws Exception {
        assertFieldTarget(MixinBWTileEntityMetaGeneratedOreLegacy.class,
            "Lbartworks/system/material/BWTileEntityMetaGeneratedOre;natural:Z",
            "bartworks/system/material/BWTileEntityMetaGeneratedOre");
    }

    private static void assertFieldTarget(Class<?> mixin, String fieldTarget, String className) throws IOException {
        byte[] bytes = classBytes(mixin);
        String constants = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(constants.contains(fieldTarget));
        assertTrue(constants.contains("this.natural"));
        assertFalse(hasClassConstant(bytes, className));
    }

    private static boolean hasClassConstant(byte[] bytes, String className) {
        final boolean[] found = {false};
        new org.spongepowered.asm.lib.ClassReader(bytes).accept(new org.spongepowered.asm.lib.ClassVisitor(Opcodes.API) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                if (className.equals(superName)) found[0] = true;
                if (interfaces != null) for (String value : interfaces) if (className.equals(value)) found[0] = true;
            }
        }, org.spongepowered.asm.lib.ClassReader.SKIP_CODE | org.spongepowered.asm.lib.ClassReader.SKIP_DEBUG);
        return found[0];
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException(resource);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static final class Opcodes {
        private static final int API = org.spongepowered.asm.lib.Opcodes.ASM5;
    }
}
