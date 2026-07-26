package club.heiqi.qz_miner.compat.adapter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** 作物三态、vanilla 映射与 adapter fail-closed 合同。 */
public class CropGrowthStateTest {

    private static final Block TEST_BLOCK = new TestBlock();

    /** 三态必须精确且稳定，不能用 boolean 折叠 UNKNOWN。 */
    @Test
    public void enumContainsExactlyThreeStates() {
        Assert.assertArrayEquals(
                new CropGrowthState[] {
                        CropGrowthState.MATURE,
                        CropGrowthState.IMMATURE,
                        CropGrowthState.UNKNOWN },
                CropGrowthState.values());
    }

    /** vanilla meta 6 可继续生长、meta 7 不可继续生长的权威返回值映射。 */
    @Test
    public void vanillaCanGrowMappingClassifiesMetadataSixAndSeven() {
        Assert.assertEquals("meta 6 的 can-grow=true 必须是未成熟",
                CropGrowthState.IMMATURE, CompatAdapters.classifyVanillaGrowth(true));
        Assert.assertEquals("meta 7 的 can-grow=false 必须是成熟",
                CropGrowthState.MATURE, CompatAdapters.classifyVanillaGrowth(false));
    }

    /** 生产 vanilla 分支必须核对 live block/meta 并调用 IGrowable 权威门。 */
    @Test
    public void vanillaProductionPathUsesLiveConsistencyAndGrowableMethod() throws Exception {
        String source = read("src/main/java/club/heiqi/qz_miner/compat/adapter/CompatAdapters.java");
        Assert.assertTrue(source.contains("world.getBlock(x, y, z) == block"));
        Assert.assertTrue(source.contains("world.getBlockMetadata(x, y, z) == metadata"));
        Assert.assertTrue(source.contains("block instanceof BlockCrops"));
        Assert.assertTrue(source.contains("((IGrowable) block).func_149851_a("));
    }

    /** 默认 adapter 不得猜测成熟度。 */
    @Test
    public void adapterDefaultIsUnknown() {
        CropCompatAdapter adapter = new RecognizingAdapter();
        Assert.assertEquals(CropGrowthState.UNKNOWN,
                adapter.growthState(null, 0, 0, 0, TEST_BLOCK, 0, null));
    }

    /** 已识别 adapter 可跳过 UNKNOWN，返回后续首个已知结果。 */
    @Test
    public void facadeReturnsFirstNonUnknownFromRecognizedAdapters() {
        CropCompatAdapter unknown = new RecognizingAdapter();
        CropCompatAdapter mature = new FixedGrowthAdapter(CropGrowthState.MATURE);
        CropCompatAdapter immature = new FixedGrowthAdapter(CropGrowthState.IMMATURE);

        CropGrowthState state = CompatAdapters.growthState(
                null, 0, 0, 0, TEST_BLOCK, 0, null, Arrays.asList(unknown, mature, immature));

        Assert.assertEquals(CropGrowthState.MATURE, state);
    }

    /** 未识别、null 返回和可选兼容异常全部 fail-closed。 */
    @Test
    public void unknownAndAdapterFailuresAreFailClosed() {
        Assert.assertEquals(CropGrowthState.UNKNOWN, CompatAdapters.growthState(
                null, 0, 0, 0, TEST_BLOCK, 0, null,
                Collections.<CropCompatAdapter>singletonList(new NonRecognizingAdapter())));
        Assert.assertEquals(CropGrowthState.UNKNOWN, CompatAdapters.growthState(
                null, 0, 0, 0, TEST_BLOCK, 0, null,
                Collections.<CropCompatAdapter>singletonList(new FixedGrowthAdapter(null))));
        Assert.assertEquals(CropGrowthState.UNKNOWN, CompatAdapters.growthState(
                null, 0, 0, 0, TEST_BLOCK, 0, null,
                Collections.<CropCompatAdapter>singletonList(new ThrowingGrowthAdapter())));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    /** 测试方块。 */
    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.plants);
        }
    }

    /** 只识别作物并使用接口默认 UNKNOWN。 */
    private static class RecognizingAdapter implements CropCompatAdapter {
        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean isCropBlock(Block block, TileEntity tileEntity) {
            return block == TEST_BLOCK;
        }
    }

    /** 不识别测试方块。 */
    private static final class NonRecognizingAdapter extends RecognizingAdapter {
        @Override
        public boolean isCropBlock(Block block, TileEntity tileEntity) {
            return false;
        }
    }

    /** 固定三态 adapter。 */
    private static final class FixedGrowthAdapter extends RecognizingAdapter {
        private final CropGrowthState state;

        private FixedGrowthAdapter(CropGrowthState state) {
            this.state = state;
        }

        @Override
        public CropGrowthState growthState(World world, int x, int y, int z, Block block, int metadata,
                TileEntity tileEntity) {
            return state;
        }
    }

    /** 模拟可选兼容调用异常。 */
    private static final class ThrowingGrowthAdapter extends RecognizingAdapter {
        @Override
        public CropGrowthState growthState(World world, int x, int y, int z, Block block, int metadata,
                TileEntity tileEntity) {
            throw new IllegalStateException("synthetic optional compat failure");
        }
    }
}
