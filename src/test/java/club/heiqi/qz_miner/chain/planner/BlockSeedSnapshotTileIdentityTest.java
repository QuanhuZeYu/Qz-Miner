package club.heiqi.qz_miner.chain.planner;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import net.minecraft.tileentity.TileEntity;

/** {@link BlockSeedSnapshot} 的纯值身份与 live-TE 兼容边界。 */
public class BlockSeedSnapshotTileIdentityTest {

    /** 服务端普通规划可构造完全不持有 seed TileEntity 的快照。 */
    @Test
    public void tokenConstructorDoesNotRetainTileEntity() {
        TileIdentityToken token = TileIdentityToken.present("runtime-class", FixtureTile.class.getName(),
                "same-runtime-type");
        BlockSeedSnapshot snapshot = new BlockSeedSnapshot(new ChainTarget(1, 2, 3), null, 4, token);

        Assert.assertSame(token, snapshot.getSampleTileIdentity());
        Assert.assertNull(snapshot.getSampleTileEntity());
    }

    /** 既有预览/特殊模式构造器保留专用字段，但同时立即冻结纯值身份。 */
    @Test
    public void liveCompatibilityConstructorAlsoCapturesToken() {
        FixtureTile tileEntity = new FixtureTile();
        BlockSeedSnapshot snapshot = new BlockSeedSnapshot(new ChainTarget(1, 2, 3), null, 4, tileEntity);

        Assert.assertSame(tileEntity, snapshot.getSampleTileEntity());
        Assert.assertEquals(TileIdentityToken.State.PRESENT, snapshot.getSampleTileIdentity().getState());
        Assert.assertEquals(FixtureTile.class.getName(), snapshot.getSampleTileIdentity().getTypeName());
    }

    /** 未知测试 TileEntity。 */
    private static final class FixtureTile extends TileEntity {}
}
