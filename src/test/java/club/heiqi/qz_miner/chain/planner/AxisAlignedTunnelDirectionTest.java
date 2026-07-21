package club.heiqi.qz_miner.chain.planner;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.entity.player.EntityPlayer;

/** 六轴、平局与命中面反向的纯函数回归。 */
public class AxisAlignedTunnelDirectionTest {

    @Test
    public void resolvesAllSixLookAxes() {
        Assert.assertEquals(5, AxisAlignedTunnelDirection.resolveFace(1, 0, 0));
        Assert.assertEquals(4, AxisAlignedTunnelDirection.resolveFace(-1, 0, 0));
        Assert.assertEquals(1, AxisAlignedTunnelDirection.resolveFace(0, 1, 0));
        Assert.assertEquals(0, AxisAlignedTunnelDirection.resolveFace(0, -1, 0));
        Assert.assertEquals(3, AxisAlignedTunnelDirection.resolveFace(0, 0, 1));
        Assert.assertEquals(2, AxisAlignedTunnelDirection.resolveFace(0, 0, -1));
    }

    @Test
    public void tieOrderIsYThenZThenXAndNullFallsBackUp() {
        Assert.assertEquals(1, AxisAlignedTunnelDirection.resolveFace(1, 1, 1));
        Assert.assertEquals(2, AxisAlignedTunnelDirection.resolveFace(1, 0, -1));
        Assert.assertEquals(1, AxisAlignedTunnelDirection.resolveFace((EntityPlayer) null));
    }

    @Test
    public void oppositeCoversSixFacesAndInvalidFallsBackLook() {
        Assert.assertArrayEquals(new int[] {1, 0, 3, 2, 5, 4}, new int[] {
                AxisAlignedTunnelDirection.oppositeFace(0),
                AxisAlignedTunnelDirection.oppositeFace(1),
                AxisAlignedTunnelDirection.oppositeFace(2),
                AxisAlignedTunnelDirection.oppositeFace(3),
                AxisAlignedTunnelDirection.oppositeFace(4),
                AxisAlignedTunnelDirection.oppositeFace(5)});
        Assert.assertEquals(-1, AxisAlignedTunnelDirection.oppositeFace(-1));
        Assert.assertEquals(4, AxisAlignedTunnelDirection.resolveHitFaceOrLook(9, 4));
        Assert.assertEquals(1, AxisAlignedTunnelDirection.resolveHitFaceOrLook(9, 9));
    }
}
