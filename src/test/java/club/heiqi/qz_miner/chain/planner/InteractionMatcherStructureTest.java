package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** 作物/液体交互 matcher 的 live 读取与无世界持有结构合同。 */
public class InteractionMatcherStructureTest {

    /** 未成熟作物 matcher 每次读取 block/meta/tile，只接受可靠 IMMATURE。 */
    @Test
    public void immatureMatcherReadsAllLiveFactsAndFailsClosed() throws Exception {
        String source = read(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ImmatureCropBlockMatcher.java");

        Assert.assertTrue(source.contains("world.getBlock(x, y, z)"));
        Assert.assertTrue(source.contains("world.getBlockMetadata(x, y, z)"));
        Assert.assertTrue(source.contains("world.getTileEntity(x, y, z)"));
        Assert.assertTrue(source.contains("ChainCropRules.isReliablyImmature("));
        assertNoLiveStateFields(ImmatureCropBlockMatcher.class);
    }

    /** 液体 matcher 只冻结 fluid identity/source，并在 matches 读取 live block/meta。 */
    @Test
    public void liquidMatcherStoresOnlyFrozenIdentityAndReadsLiveCandidate() throws Exception {
        String source = read(
                "src/main/java/club/heiqi/qz_miner/chain/planner/LiquidSourceBlockMatcher.java");

        Assert.assertTrue(source.contains("private final String seedFluidIdentity"));
        Assert.assertTrue(source.contains("private final boolean seedSource"));
        Assert.assertTrue(source.contains("world.getBlock(x, y, z)"));
        Assert.assertTrue(source.contains("world.getBlockMetadata(x, y, z)"));
        Assert.assertTrue(source.contains("ChainLiquidRules.matchesSource("));
        Assert.assertFalse(source.contains("getTileEntity("));
        Assert.assertFalse(source.contains(".drain("));
        assertNoLiveStateFields(LiquidSourceBlockMatcher.class);

        for (Field field : LiquidSourceBlockMatcher.class.getDeclaredFields()) {
            Assert.assertFalse("matcher 不得保留 seed Block", Block.class.isAssignableFrom(field.getType()));
        }
        for (Constructor<?> constructor : LiquidSourceBlockMatcher.class.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                Assert.assertFalse("matcher 构造器不得接收 live World",
                        World.class.isAssignableFrom(parameterType));
                Assert.assertFalse("matcher 构造器不得接收 live TileEntity",
                        TileEntity.class.isAssignableFrom(parameterType));
            }
        }
    }

    private static void assertNoLiveStateFields(Class<?> matcherType) {
        for (Field field : matcherType.getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            Assert.assertFalse("matcher 不得持有 World", World.class.isAssignableFrom(fieldType));
            Assert.assertFalse("matcher 不得持有 TileEntity", TileEntity.class.isAssignableFrom(fieldType));
        }
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
