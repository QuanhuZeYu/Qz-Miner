package club.heiqi.qz_miner.chain.mode;

import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import club.heiqi.qz_miner.chain.executor.LiquidSourceInteractActionExecutor;
import club.heiqi.qz_miner.chain.executor.TargetRevalidatingBlockInteractActionExecutor;
import club.heiqi.qz_miner.chain.planner.BoxScanTraverser;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcher;
import club.heiqi.qz_miner.chain.planner.ChainCandidateFilter;
import club.heiqi.qz_miner.chain.planner.ChainResolverContext;
import club.heiqi.qz_miner.chain.planner.ChainSearchContext;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.planner.CropBlockMatcher;
import club.heiqi.qz_miner.chain.planner.ImmatureCropBlockMatcher;
import club.heiqi.qz_miner.chain.planner.LiquidSourceBlockMatcher;
import club.heiqi.qz_miner.chain.planner.SameBlockMatcher;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

/** 四种范围交互子模式的顺序、展示与 resolver 实际接线回归。 */
public class ChainInteractSubModeWiringTest {

    private static final ChainTarget ORIGIN = new ChainTarget(0, 64, 0);
    private static final Block SAMPLE_BLOCK = new TestBlock();

    @BeforeClass
    public static void bootstrapModes() {
        ChainSubModeRegistry.clearDefinitions();
        ChainModeBootstrap.bootstrap();
        ChainSubModeBootstrap.bootstrap();
    }

    @Test
    public void interactKeepsOrdinalDefaultOrderAndCubePresentation() {
        ChainModeDefinition definition = ChainModeRegistry.getDefinition(ChainMode.INTERACT);

        Assert.assertEquals(2, ChainMode.INTERACT.ordinal());
        Assert.assertEquals(ChainSubMode.INTERACT_BASE, definition.getDefaultSubMode());
        Assert.assertEquals(Arrays.asList(
                ChainSubMode.INTERACT_BASE,
                ChainSubMode.INTERACT_LIQUID_SOURCE,
                ChainSubMode.INTERACT_CROP,
                ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP), definition.getSubModes());
        Assert.assertTrue(definition.shouldShowAreaInfo());
        Assert.assertArrayEquals(new int[] {7, 7, 7},
                definition.resolveAreaDimensions(3, ChainSubMode.INTERACT_BASE));
        Assert.assertEquals("hud.qz_miner.sub_mode.interact.liquid_source",
                ChainSubMode.INTERACT_LIQUID_SOURCE.getDisplayNameKey());
        Assert.assertEquals("hud.qz_miner.sub_mode.interact.fertilize_immature_crop",
                ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP.getDisplayNameKey());
    }

    @Test
    public void allFourModesResolveRightClickBoxScanAndExplicitCandidates() {
        for (ChainSubMode subMode : interactSubModes()) {
            ChainSearchContext searchContext = searchContext(subMode);
            ChainResolverContext resolverContext = new ChainResolverContext(null, null, searchContext);
            ChainCandidateFilter fallback = target -> false;
            ChainCandidateFilter candidate = ChainSubModeRegistry.createCandidateFilter(
                    searchContext, fallback);

            Assert.assertEquals(subMode.name(), ChainSubModeTrigger.RIGHT_CLICK,
                    ChainSubModeRegistry.getTrigger(subMode));
            Assert.assertTrue(subMode.name(), ChainModeRegistry.getDefinition(ChainMode.INTERACT)
                    .createTraverser(resolverContext) instanceof BoxScanTraverser);
            Assert.assertNotNull(subMode.name(), candidate);
            Assert.assertNotSame(subMode.name(), fallback, candidate);
        }
    }

    @Test
    public void eachModeResolvesItsMatcherAndExecutor() {
        assertWiring(ChainSubMode.INTERACT_BASE, SameBlockMatcher.class,
                TargetRevalidatingBlockInteractActionExecutor.class);
        assertWiring(ChainSubMode.INTERACT_LIQUID_SOURCE, LiquidSourceBlockMatcher.class,
                LiquidSourceInteractActionExecutor.class);
        assertWiring(ChainSubMode.INTERACT_CROP, CropBlockMatcher.class,
                TargetRevalidatingBlockInteractActionExecutor.class);
        assertWiring(ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP,
                ImmatureCropBlockMatcher.class,
                TargetRevalidatingBlockInteractActionExecutor.class);
    }

    private static void assertWiring(ChainSubMode subMode, Class<?> matcherType, Class<?> executorType) {
        ChainSearchContext searchContext = searchContext(subMode);
        ChainResolverContext resolverContext = new ChainResolverContext(null, null, searchContext);
        ChainBlockMatcher matcher = ChainModeRegistry.getDefinition(ChainMode.INTERACT)
                .createMatcher(resolverContext);

        Assert.assertEquals(subMode.name(), matcherType, matcher.getClass());
        Assert.assertEquals(subMode.name(), executorType,
                ChainModeRegistry.getDefinition(ChainMode.INTERACT)
                        .resolveActionExecutor(subMode).getClass());
    }

    private static ChainSubMode[] interactSubModes() {
        return new ChainSubMode[] {
                ChainSubMode.INTERACT_BASE,
                ChainSubMode.INTERACT_LIQUID_SOURCE,
                ChainSubMode.INTERACT_CROP,
                ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP
        };
    }

    private static ChainSearchContext searchContext(ChainSubMode subMode) {
        return new ChainSearchContext(
                null,
                ORIGIN,
                SAMPLE_BLOCK,
                0,
                TileIdentityToken.absent(),
                null,
                subMode,
                3,
                64,
                new ConcurrentLinkedQueue<ChainTarget>(),
                new ConcurrentLinkedQueue<ChainTarget>(),
                new HashSet<ChainTarget>(),
                ModeExtensionSnapshot.EMPTY);
    }

    /** 不触碰 world 的稳定测试 seed 方块。 */
    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.rock);
        }
    }
}
