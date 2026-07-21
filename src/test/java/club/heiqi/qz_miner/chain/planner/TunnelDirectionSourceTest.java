package club.heiqi.qz_miner.chain.planner;

import org.junit.Assert;
import org.junit.Test;

/** 稳定配置 id 与 wire code 合同。 */
public class TunnelDirectionSourceTest {

    @Test
    public void idsAndWireCodesAreStableAndStrict() {
        Assert.assertArrayEquals(new String[] {"look_direction", "hit_face"}, TunnelDirectionSource.ids());
        Assert.assertEquals(TunnelDirectionSource.LOOK_DIRECTION,
                TunnelDirectionSource.fromId("look_direction"));
        Assert.assertEquals(TunnelDirectionSource.HIT_FACE, TunnelDirectionSource.fromWireCode(1));
        Assert.assertNull(TunnelDirectionSource.fromId("LOOK"));
        Assert.assertNull(TunnelDirectionSource.fromWireCode(2));
        Assert.assertEquals(TunnelDirectionSource.LOOK_DIRECTION, TunnelDirectionSource.legacyDefault());
    }
}
