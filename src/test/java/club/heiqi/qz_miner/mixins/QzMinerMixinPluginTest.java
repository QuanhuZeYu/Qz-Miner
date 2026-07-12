package club.heiqi.qz_miner.mixins;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

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
