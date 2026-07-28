package club.heiqi.qz_miner.chain.client;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.network.PacketCuboidSelectionSync;

/** 客户端只接受可排序完整 ACK，并在 lifecycle 清空。 */
public class ClientCuboidSelectionStateTest {

    @Test
    public void staleAndMalformedAcksCannotReplaceCurrentProjection() {
        ClientCuboidSelectionState state = new ClientCuboidSelectionState();
        Assert.assertTrue(state.publish(PacketCuboidSelectionSync.PROTOCOL_VERSION, 2L, 1, 3,
                0, 1, 2, 3, 0, 4, 5, 6, true));
        Assert.assertEquals(64L, state.bounds().volume());
        Assert.assertFalse(state.publish(PacketCuboidSelectionSync.PROTOCOL_VERSION, 1L, 1, 0,
                0, 0, 0, 0, 0, 0, 0, 0, true));
        Assert.assertEquals(64L, state.bounds().volume());
        state.clear();
        Assert.assertNull(state.bounds());
    }
}
