package club.heiqi.qz_miner.chain.state;

import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

/** 交互 seed 在 request/session 中的不可变纯值传播合同。 */
public class ChainRequestInteractionSeedTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final ChainTarget ORIGIN = new ChainTarget(1, 2, 3);
    private static final Block SEED_BLOCK = new TestBlock();

    /** 所有旧构造器都必须保持 null/0/UNRESOLVED 安全默认。 */
    @Test
    public void legacyConstructorsUseFailClosedSeedDefaults() {
        ChainRequest request = new ChainRequest(
                PLAYER, ChainMode.INTERACT, ChainSubMode.INTERACT_BASE, ORIGIN);
        ChainSession session = new ChainSession(
                PLAYER, ChainMode.INTERACT, ChainSubMode.INTERACT_BASE, ORIGIN,
                1, 0.25F, 0.5F, 0.75F, 16, 64, ModeExtensionSnapshot.EMPTY);

        assertDefaultSeed(request.getSeedBlock(), request.getSeedMeta(), request.getSeedTileIdentity());
        assertDefaultSeed(session.getSeedBlock(), session.getSeedMeta(), session.getSeedTileIdentity());
    }

    /** 新全参数构造器必须保留完整 int 与同一不可变 token。 */
    @Test
    public void fullConstructorPreservesCompleteSeedValues() {
        TileIdentityToken token = TileIdentityToken.present("test", "test.Tile", "identity");
        ChainRequest request = new ChainRequest(
                PLAYER, ChainMode.INTERACT, ChainSubMode.INTERACT_BASE, ORIGIN,
                2, 0.1F, 0.2F, 0.3F, 32, 512, ModeExtensionSnapshot.EMPTY,
                SEED_BLOCK, Integer.MAX_VALUE, token);
        ChainSession session = new ChainSession(request);

        Assert.assertSame(SEED_BLOCK, request.getSeedBlock());
        Assert.assertEquals(Integer.MAX_VALUE, request.getSeedMeta());
        Assert.assertSame(token, request.getSeedTileIdentity());
        Assert.assertSame(request.getSeedBlock(), session.getSeedBlock());
        Assert.assertEquals(request.getSeedMeta(), session.getSeedMeta());
        Assert.assertSame(request.getSeedTileIdentity(), session.getSeedTileIdentity());
    }

    /** null token 不得进入会话；必须归一为 fail-closed UNRESOLVED。 */
    @Test
    public void nullTokenIsNormalizedToUnresolved() {
        ChainRequest request = new ChainRequest(
                PLAYER, ChainMode.INTERACT, ChainSubMode.INTERACT_BASE, ORIGIN,
                1, 0F, 0F, 0F, 8, 8, ModeExtensionSnapshot.EMPTY,
                SEED_BLOCK, 0, null);

        Assert.assertSame(TileIdentityToken.unresolved(), request.getSeedTileIdentity());
    }

    /** 负 metadata 不属于冻结 seed 合同。 */
    @Test(expected = IllegalArgumentException.class)
    public void negativeSeedMetadataIsRejected() {
        new ChainRequest(
                PLAYER, ChainMode.INTERACT, ChainSubMode.INTERACT_BASE, ORIGIN,
                1, 0F, 0F, 0F, 8, 8, ModeExtensionSnapshot.EMPTY,
                SEED_BLOCK, -1, TileIdentityToken.absent());
    }

    private static void assertDefaultSeed(Object block, int metadata, TileIdentityToken token) {
        Assert.assertNull(block);
        Assert.assertEquals(0, metadata);
        Assert.assertNotNull(token);
        Assert.assertSame(TileIdentityToken.unresolved(), token);
    }

    /** 非 null seed 方块。 */
    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.rock);
        }
    }
}
