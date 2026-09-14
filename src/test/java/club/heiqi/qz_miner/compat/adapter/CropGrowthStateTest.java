package club.heiqi.qz_miner.compat.adapter;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/** 作物三态、vanilla 映射与 adapter fail-closed 合同。 */
public class CropGrowthStateTest {

    private static final String COMPAT_ADAPTERS_SOURCE =
            "src/main/java/club/heiqi/qz_miner/compat/adapter/CompatAdapters.java";

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

    /**
     * 生产 vanilla 分支必须核对 live block/meta 并调用 IGrowable 权威门。
     *
     * <p>一致性判定本身是纯计算，已下沉为包级纯函数并按真值表证伪；
     * 「live 世界必须被重读」「BlockCrops 才走 vanilla 分支」「必须查询 IGrowable 权威门」
     * 这三条落在生产方法体上做结构断言。</p>
     */
    @Test
    public void vanillaProductionPathUsesLiveConsistencyAndGrowableMethod() throws Exception {
        Assert.assertTrue("同实例同 metadata 必须判为一致",
                CompatAdapters.matchesLiveBlock(TEST_BLOCK, 6, TEST_BLOCK, 6));
        Assert.assertFalse("块漂移必须拒绝（否则会按陈旧方块判成熟度）",
                CompatAdapters.matchesLiveBlock(new TestBlock(), 6, TEST_BLOCK, 6));
        Assert.assertFalse("metadata 漂移必须拒绝（否则 meta 6/7 会串味）",
                CompatAdapters.matchesLiveBlock(TEST_BLOCK, 7, TEST_BLOCK, 6));
        Assert.assertFalse("期望 metadata 非法必须拒绝",
                CompatAdapters.matchesLiveBlock(TEST_BLOCK, 6, TEST_BLOCK, -1));
        Assert.assertFalse("live 方块缺失必须拒绝",
                CompatAdapters.matchesLiveBlock(null, 0, TEST_BLOCK, 0));

        String masked = JavaSourceSlices.maskedMainSource(COMPAT_ADAPTERS_SOURCE);
        String liveRead = JavaSourceSlices.methodBody(masked,
                "private static boolean matchesLiveBlock(World world", "CompatAdapters.matchesLiveBlock");
        JavaSourceSlices.assertContains(liveRead, "world.getBlock(",
                "一致性判定必须重读 live block");
        JavaSourceSlices.assertContains(liveRead, "world.getBlockMetadata(",
                "一致性判定必须重读 live metadata");

        String growth = JavaSourceSlices.methodBody(masked,
                "public static CropGrowthState growthState(World world", "CompatAdapters.growthState");
        String cropsBranch = JavaSourceSlices.blockAfter(growth, "instanceof BlockCrops");
        Assert.assertFalse("必须保留 BlockCrops 分支", cropsBranch.isEmpty());
        JavaSourceSlices.assertContains(cropsBranch, "vanillaCropGrowthState(",
                "BlockCrops 分支必须委托 vanilla 权威门");

        // 不做行为化的原因：func_149851_a 必须带真实 world 与坐标（作物的 canGrow 依赖环境），
        // 去掉 world 的「纯函数接缝」会改变生产语义，而 headless 造 World 需要新增 fixture（裁定 A 不允许）。
        // can-grow → 三态的映射部分已由 classifyVanillaGrowth 的真值表用例覆盖。
        String vanilla = JavaSourceSlices.methodBody(masked,
                "private static CropGrowthState vanillaCropGrowthState(World world",
                "CompatAdapters.vanillaCropGrowthState");
        JavaSourceSlices.assertContains(vanilla, "func_149851_a",
                "vanilla 分支必须查询 IGrowable 权威门，不得用 metadata 猜成熟度");
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
