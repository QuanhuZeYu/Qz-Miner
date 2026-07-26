package club.heiqi.qz_miner.chain.executor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcher;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.planner.CropBlockMatcher;
import club.heiqi.qz_miner.chain.planner.ImmatureCropBlockMatcher;
import club.heiqi.qz_miner.chain.planner.SameBlockMatcher;
import club.heiqi.qz_miner.chain.state.ChainRequest;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;
import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.qz_miner.objectgroup.ObjectGroupSelector;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;

/** 通用目标重验执行器的 matcher 选择、冻结 seed 与 fail-closed 结构合同。 */
public class TargetRevalidatingBlockInteractActionExecutorStructureTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000713");
    private static final ChainTarget ORIGIN = new ChainTarget(1, 2, 3);
    private static final Block SEED_BLOCK = new TestBlock();

    @BeforeClass
    public static void bootstrapModes() {
        ChainModeBootstrap.bootstrap();
    }

    @Test
    public void emptyExtensionsSelectSameCropAndReliableImmatureMatchers() {
        Assert.assertTrue(matcher(ChainSubMode.INTERACT_BASE, ModeExtensionSnapshot.EMPTY)
                instanceof SameBlockMatcher);
        Assert.assertTrue(matcher(ChainSubMode.INTERACT_CROP, ModeExtensionSnapshot.EMPTY)
                instanceof CropBlockMatcher);
        Assert.assertTrue(matcher(ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP,
                ModeExtensionSnapshot.EMPTY) instanceof ImmatureCropBlockMatcher);
    }

    @Test
    public void objectGroupDecoratesOnlyLegacyBaseAndCrop() {
        ModeExtensionSnapshot extension = ModeExtensionSnapshot.from(new ObjectGroup(
                "interaction-extension",
                Collections.singletonList(ObjectGroupMode.INTERACT_BASE),
                32L,
                Collections.singletonList(ObjectGroupSelector.single("test:block", 0))));

        Assert.assertFalse(matcher(ChainSubMode.INTERACT_BASE, extension) instanceof SameBlockMatcher);
        Assert.assertFalse(matcher(ChainSubMode.INTERACT_CROP, extension) instanceof CropBlockMatcher);
        Assert.assertTrue(matcher(ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP, extension)
                instanceof ImmatureCropBlockMatcher);
    }

    @Test
    public void missingUnknownOrMismatchedRequestIdentityFailsClosed() {
        ChainRequest legacy = new ChainRequest(
                PLAYER, ChainMode.INTERACT, ChainSubMode.INTERACT_BASE, ORIGIN);
        ChainRequest unresolved = request(
                ChainSubMode.INTERACT_BASE, ModeExtensionSnapshot.EMPTY,
                SEED_BLOCK, TileIdentityToken.unresolved());
        ChainRequest air = request(
                ChainSubMode.INTERACT_BASE, ModeExtensionSnapshot.EMPTY,
                Blocks.air, TileIdentityToken.absent());
        ChainRequest base = request(
                ChainSubMode.INTERACT_BASE, ModeExtensionSnapshot.EMPTY,
                SEED_BLOCK, TileIdentityToken.absent());

        Assert.assertNull(TargetRevalidatingBlockInteractActionExecutor.createLiveMatcher(
                null, ChainSubMode.INTERACT_BASE));
        Assert.assertNull(TargetRevalidatingBlockInteractActionExecutor.createLiveMatcher(
                legacy, ChainSubMode.INTERACT_BASE));
        Assert.assertNull(TargetRevalidatingBlockInteractActionExecutor.createLiveMatcher(
                unresolved, ChainSubMode.INTERACT_BASE));
        Assert.assertNull(TargetRevalidatingBlockInteractActionExecutor.createLiveMatcher(
                air, ChainSubMode.INTERACT_BASE));
        Assert.assertNull(TargetRevalidatingBlockInteractActionExecutor.createLiveMatcher(
                base, ChainSubMode.INTERACT_CROP));
        Assert.assertNull(TargetRevalidatingBlockInteractActionExecutor.createLiveMatcher(
                request(ChainSubMode.INTERACT_LIQUID_SOURCE, ModeExtensionSnapshot.EMPTY,
                        SEED_BLOCK, TileIdentityToken.absent()),
                ChainSubMode.INTERACT_LIQUID_SOURCE));
    }

    @Test
    public void executorReusesPermissionAndTargetedActivationAndCatchesModFailures() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/executor/"
                        + "TargetRevalidatingBlockInteractActionExecutor.java").toPath()),
                StandardCharsets.UTF_8);
        int permission = source.indexOf("super.canExecute(player, session, target)");
        int matcher = source.indexOf("createLiveMatcher(session.getRequest(), expectedSubMode)");

        Assert.assertTrue(source.contains("extends BlockInteractActionExecutor"));
        Assert.assertTrue(permission >= 0 && matcher > permission);
        Assert.assertTrue(source.contains("catch (RuntimeException | LinkageError failure)"));
        Assert.assertFalse("目标化右键实现必须只复用 generic executor",
                source.contains("activateBlockOrUseItem("));
        Assert.assertFalse("液体 Item 路径不属于该执行器", source.contains("tryUseItem("));
    }

    private static ChainBlockMatcher matcher(ChainSubMode subMode, ModeExtensionSnapshot extension) {
        return TargetRevalidatingBlockInteractActionExecutor.createLiveMatcher(
                request(subMode, extension, SEED_BLOCK, TileIdentityToken.absent()), subMode);
    }

    private static ChainRequest request(ChainSubMode subMode, ModeExtensionSnapshot extension,
            Block seedBlock, TileIdentityToken seedTileIdentity) {
        return new ChainRequest(
                PLAYER,
                ChainMode.INTERACT,
                subMode,
                ORIGIN,
                1,
                0.25F,
                0.5F,
                0.75F,
                8,
                64,
                extension,
                seedBlock,
                0,
                seedTileIdentity);
    }

    /** 非空气且无 TileEntity 的测试 seed。 */
    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.rock);
        }
    }
}
