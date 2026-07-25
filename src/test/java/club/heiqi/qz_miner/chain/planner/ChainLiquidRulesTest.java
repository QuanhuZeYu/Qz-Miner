package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidBlock;

/** vanilla/Forge 流体身份与静态可排液 source 矩阵。 */
public class ChainLiquidRulesTest {

    /** vanilla water/lava 只接受同种 metadata 0 source。 */
    @Test
    public void vanillaSourcesRequireMetadataZeroAndSameFluid() {
        Block waterBlock = new TestVanillaLiquidBlock(Material.water);
        Block flowingWaterBlock = new TestVanillaLiquidBlock(Material.water);
        Block lavaBlock = new TestVanillaLiquidBlock(Material.lava);
        String water = ChainLiquidRules.fluidIdentity(waterBlock);
        String lava = ChainLiquidRules.fluidIdentity(lavaBlock);

        Assert.assertEquals("water", water);
        Assert.assertEquals("lava", lava);
        Assert.assertTrue(ChainLiquidRules.isSeedSource(waterBlock, 0));
        Assert.assertFalse(ChainLiquidRules.isSeedSource(waterBlock, 1));
        Assert.assertTrue(ChainLiquidRules.matchesSource(water, null, 1, 2, 3, waterBlock, 0));
        Assert.assertFalse("流动水不得匹配",
                ChainLiquidRules.matchesSource(water, null, 1, 2, 3, flowingWaterBlock, 1));
        Assert.assertFalse("不同流体不得匹配",
                ChainLiquidRules.matchesSource(water, null, 1, 2, 3, lavaBlock, 0));
    }

    /** Forge source 由同一 fluid identity 与 live canDrain 联合终裁。 */
    @Test
    public void forgeSourcesUseFluidIdentityAndCanDrain() {
        Fluid waterLike = new Fluid("qz_test_water_like");
        TestFluidBlock seed = new TestFluidBlock(waterLike, true, false, false);
        TestFluidBlock source = new TestFluidBlock(waterLike, true, false, false);
        TestFluidBlock flowing = new TestFluidBlock(waterLike, false, false, false);
        TestFluidBlock other = new TestFluidBlock(new Fluid("qz_test_other"), true, false, false);
        String identity = ChainLiquidRules.fluidIdentity(seed);

        Assert.assertTrue("有限流体 seed 不应被 vanilla metadata 规则误杀",
                ChainLiquidRules.isSeedSource(seed, 7));
        Assert.assertTrue(ChainLiquidRules.matchesSource(identity, null, 4, 5, 6, source, 7));
        Assert.assertFalse(ChainLiquidRules.matchesSource(identity, null, 4, 5, 6, flowing, 7));
        Assert.assertFalse(ChainLiquidRules.matchesSource(identity, null, 4, 5, 6, other, 7));
        Assert.assertEquals(1, source.canDrainCalls);
        Assert.assertEquals(1, flowing.canDrainCalls);
        Assert.assertEquals("不同 fluid 必须在 canDrain 前拒绝", 0, other.canDrainCalls);
        Assert.assertEquals(0, source.drainCalls);
        Assert.assertEquals(0, flowing.drainCalls);
    }

    /** getFluid/canDrain 异常与未知 identity 一律 false。 */
    @Test
    public void forgeFailuresAreFailClosed() {
        Fluid fluid = new Fluid("qz_test_failure");
        TestFluidBlock throwingIdentity = new TestFluidBlock(fluid, true, true, false);
        TestFluidBlock throwingCanDrain = new TestFluidBlock(fluid, true, false, true);

        Assert.assertNull(ChainLiquidRules.fluidIdentity(throwingIdentity));
        Assert.assertFalse(ChainLiquidRules.matchesSource(
                fluid.getName(), null, 0, 0, 0, throwingCanDrain, 0));
        Assert.assertEquals(0, throwingCanDrain.drainCalls);
        Assert.assertFalse(ChainLiquidRules.matchesSource(
                null, null, 0, 0, 0, new TestVanillaLiquidBlock(Material.water), 0));
        Assert.assertFalse(ChainLiquidRules.isSeedSource(null, 0));
    }

    /** matcher 构造时只采样一次 seed fluid，随后不持有 seed Block。 */
    @Test
    public void matcherFreezesSeedFluidIdentityOnce() {
        TestFluidBlock seed = new TestFluidBlock(
                new Fluid("qz_test_single_seed_read"), true, false, false);

        new LiquidSourceBlockMatcher(seed, 9);

        Assert.assertEquals(1, seed.getFluidCalls);
    }

    /** 生产规则只探测 canDrain，禁止用 drain(false) 或其它调用修改世界。 */
    @Test
    public void productionNeverCallsDrain() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainLiquidRules.java").toPath()),
                StandardCharsets.UTF_8);
        Assert.assertTrue(source.contains("FluidRegistry.lookupFluidForBlock(block)"));
        Assert.assertTrue(source.contains(".canDrain(world, x, y, z)"));
        Assert.assertFalse(source.contains(".drain("));
    }

    /** 可控 Forge IFluidBlock。 */
    private static final class TestFluidBlock extends Block implements IFluidBlock {
        private final Fluid fluid;
        private final boolean drainable;
        private final boolean throwOnGetFluid;
        private final boolean throwOnCanDrain;
        private int getFluidCalls;
        private int canDrainCalls;
        private int drainCalls;

        private TestFluidBlock(Fluid fluid, boolean drainable, boolean throwOnGetFluid,
                boolean throwOnCanDrain) {
            super(Material.water);
            this.fluid = fluid;
            this.drainable = drainable;
            this.throwOnGetFluid = throwOnGetFluid;
            this.throwOnCanDrain = throwOnCanDrain;
        }

        @Override
        public Fluid getFluid() {
            getFluidCalls++;
            if (throwOnGetFluid) {
                throw new IllegalStateException("synthetic getFluid failure");
            }
            return fluid;
        }

        @Override
        public boolean canDrain(World world, int x, int y, int z) {
            canDrainCalls++;
            if (throwOnCanDrain) {
                throw new IllegalStateException("synthetic canDrain failure");
            }
            return drainable;
        }

        @Override
        public FluidStack drain(World world, int x, int y, int z, boolean doDrain) {
            drainCalls++;
            return null;
        }

        @Override
        public float getFilledPercentage(World world, int x, int y, int z) {
            return drainable ? 1.0F : 0.5F;
        }
    }

    /** 可构造的 vanilla BlockLiquid 形状。 */
    private static final class TestVanillaLiquidBlock extends BlockLiquid {
        private TestVanillaLiquidBlock(Material material) {
            super(material);
        }
    }

}
