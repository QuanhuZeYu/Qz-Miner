package club.heiqi.qz_miner.compat.adapter;

import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.tileentity.TileEntity;

/** 通用 TileEntity 身份捕获、适配器降级与未知模组回归。 */
public class CompatAdaptersTileIdentityTest {

    /** 无 TileEntity 必须稳定捕获 ABSENT，且既有 live-TE 兼容壳保持通过。 */
    @Test
    public void noTileEntityCapturesAbsentAndStillMatches() {
        Assert.assertSame(TileIdentityToken.absent(), CompatAdapters.captureTileIdentity(null));
        Assert.assertTrue(CompatAdapters.matchesTileEntity(null, null));
    }

    /** 字段适配器只把纯值键写入 token，不保留原 TileEntity。 */
    @Test
    public void reflectiveFieldAdapterCapturesPureValueIdentity() {
        ReflectiveFieldTileIdentityCompatAdapter adapter = new ReflectiveFieldTileIdentityCompatAdapter(
                KnownFieldTile.class.getName(), "identity");

        TileIdentityToken token = adapter.capture(new KnownFieldTile(37));

        Assert.assertEquals(TileIdentityToken.State.PRESENT, token.getState());
        Assert.assertEquals(KnownFieldTile.class.getName(), token.getTypeName());
        Assert.assertEquals("37", token.getIdentityKey());
    }

    /** 已识别适配器读取失败必须 UNRESOLVED，禁止降级为同 runtime class 放行。 */
    @Test
    public void recognizedAdapterFailureDoesNotFallBackToRuntimeClass() {
        ReflectiveFieldTileIdentityCompatAdapter brokenAdapter = new ReflectiveFieldTileIdentityCompatAdapter(
                KnownFieldTile.class.getName(), "missingIdentity");

        TileIdentityToken first = CompatAdapters.captureTileIdentity(
                new KnownFieldTile(1), Collections.<TileIdentityCompatAdapter>singletonList(brokenAdapter));
        TileIdentityToken second = CompatAdapters.captureTileIdentity(
                new KnownFieldTile(1), Collections.<TileIdentityCompatAdapter>singletonList(brokenAdapter));

        Assert.assertSame(TileIdentityToken.unresolved(), first);
        Assert.assertSame(TileIdentityToken.unresolved(), second);
        Assert.assertFalse(CompatAdapters.matchesTileIdentity(first, second));
    }

    /** 未知模组沿既有 runtime class 语义：同类通过，不同类拒绝。 */
    @Test
    public void unknownTilesUseRuntimeTypeNameWithoutWhitelist() {
        TileIdentityToken first = CompatAdapters.captureTileIdentity(new UnknownTile());
        TileIdentityToken sameType = CompatAdapters.captureTileIdentity(new UnknownTile());
        TileIdentityToken differentType = CompatAdapters.captureTileIdentity(new OtherUnknownTile());

        Assert.assertEquals(TileIdentityToken.State.PRESENT, first.getState());
        Assert.assertEquals(UnknownTile.class.getName(), first.getTypeName());
        Assert.assertTrue(CompatAdapters.matchesTileIdentity(first, sameType));
        Assert.assertFalse(CompatAdapters.matchesTileIdentity(first, differentType));
    }

    /** 测试字段型已识别 TileEntity。 */
    private static final class KnownFieldTile extends TileEntity {
        private final int identity;

        private KnownFieldTile(int identity) {
            this.identity = identity;
        }
    }

    /** 测试未知 TileEntity。 */
    private static final class UnknownTile extends TileEntity {}

    /** 测试另一种未知 TileEntity。 */
    private static final class OtherUnknownTile extends TileEntity {}
}
