package club.heiqi.qz_miner.client;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import club.heiqi.qz_miner.network.PacketChainConfigSync;

/** S2C 配置同步在客户端主线程执行整包校验。 */
public class ClientChainConfigSyncDispatchTest {

    @Test
    public void validPacketPublishesAtomicallyInsideDispatchedTask() {
        Harness harness = dispatch(12, 345, 67);

        Assert.assertTrue(harness.accepted);
        Assert.assertArrayEquals(new int[] {91, 92, 93}, harness.state);
        harness.queued.run();

        Assert.assertArrayEquals(new int[] {12, 345, 67}, harness.state);
        Assert.assertEquals(1, harness.publications);
    }

    @Test
    public void loginPacketWithZeroMatchedCountIsValid() {
        Harness harness = dispatch(12, 345, 0);

        harness.queued.run();

        Assert.assertArrayEquals(new int[] {12, 345, 0}, harness.state);
        Assert.assertEquals(1, harness.publications);
    }

    @Test
    public void nonPositiveRadiusRejectsWholePacketAndPreservesOldState() {
        assertRejected(0, 345, 67);
        assertRejected(-1, 345, 67);
    }

    @Test
    public void nonPositiveMaxBlocksRejectsWholePacketAndPreservesOldState() {
        assertRejected(12, 0, 67);
        assertRejected(12, -1, 67);
    }

    @Test
    public void negativeMatchedCountRejectsWholePacketAndPreservesOldState() {
        assertRejected(12, 345, -1);
    }

    @Test
    public void matchedCountAboveMaxBlocksIsStillAccepted() {
        // 规划启动后服务端配置可能下调；禁止 matchedCount<=maxBlocks 硬拒绝
        Harness harness = dispatch(12, 10, 50);
        harness.queued.run();
        Assert.assertArrayEquals(new int[] {12, 10, 50}, harness.state);
        Assert.assertEquals(1, harness.publications);
    }

    @Test
    public void dispatcherRejectionPropagatesAsFalseWithoutPublication() {
        final Harness harness = new Harness();
        boolean accepted = ClientChainConfigSyncDispatch.dispatch(
                12,
                345,
                67,
                new ClientChainConfigSyncDispatch.Dispatcher() {
                    @Override
                    public boolean dispatch(Runnable task) {
                        return false;
                    }
                },
                new ClientChainConfigSyncDispatch.Publication() {
                    @Override
                    public void publish(int publishedRadius, int publishedMaxBlocks, int publishedMatchedCount) {
                        harness.publications++;
                    }
                });
        Assert.assertFalse(accepted);
        Assert.assertEquals(0, harness.publications);
        Assert.assertArrayEquals(new int[] {91, 92, 93}, harness.state);
    }

    @Test
    public void extendedAckPublishesAcceptedSourceAtomically() {
        final Harness harness = dispatchExtended(PacketChainConfigSync.PROTOCOL_VERSION,
                TunnelDirectionSource.HIT_FACE.wireCode(), true);
        Assert.assertEquals(TunnelDirectionSource.HIT_FACE, harness.source);
        harness.queued.run();
        Assert.assertArrayEquals(new int[] {12, 345, 67}, harness.state);
        Assert.assertEquals(TunnelDirectionSource.HIT_FACE, harness.source);
        Assert.assertEquals(1, harness.publications);
    }

    @Test
    public void legacyAckForcesLookAndInvalidExtendedPacketPreservesAllFourFields() {
        Harness legacy = dispatchExtended(PacketChainConfigSync.LEGACY_PROTOCOL_VERSION, 99, true);
        legacy.queued.run();
        Assert.assertEquals(TunnelDirectionSource.LOOK_DIRECTION, legacy.source);

        for (int[] raw : new int[][] {
                {99, 1, 1},
                {PacketChainConfigSync.PROTOCOL_VERSION, 99, 1},
                {PacketChainConfigSync.PROTOCOL_VERSION, 1, 0}}) {
            Harness invalid = dispatchExtended(raw[0], raw[1], raw[2] == 1);
            invalid.queued.run();
            Assert.assertArrayEquals(new int[] {91, 92, 93}, invalid.state);
            Assert.assertEquals(TunnelDirectionSource.HIT_FACE, invalid.source);
            Assert.assertEquals(0, invalid.publications);
        }
    }

    private static void assertRejected(int radius, int maxBlocks, int matchedCount) {
        Harness harness = dispatch(radius, maxBlocks, matchedCount);

        harness.queued.run();

        Assert.assertArrayEquals("invalid packet must preserve all three old fields",
                new int[] {91, 92, 93}, harness.state);
        Assert.assertEquals(0, harness.publications);
    }

    private static Harness dispatch(int radius, int maxBlocks, int matchedCount) {
        final Harness harness = new Harness();
        harness.accepted = ClientChainConfigSyncDispatch.dispatch(
                radius,
                maxBlocks,
                matchedCount,
                new ClientChainConfigSyncDispatch.Dispatcher() {
                    @Override
                    public boolean dispatch(Runnable task) {
                        harness.queued = task;
                        return true;
                    }
                },
                new ClientChainConfigSyncDispatch.Publication() {
                    @Override
                    public void publish(int publishedRadius, int publishedMaxBlocks, int publishedMatchedCount) {
                        harness.state[0] = publishedRadius;
                        harness.state[1] = publishedMaxBlocks;
                        harness.state[2] = publishedMatchedCount;
                        harness.publications++;
                    }
                });
        Assert.assertNotNull(harness.queued);
        return harness;
    }

    private static Harness dispatchExtended(int protocolVersion, int directionCode, boolean rawValid) {
        final Harness harness = new Harness();
        harness.accepted = ClientChainConfigSyncDispatch.dispatch(
                12, 345, 67, protocolVersion, directionCode, rawValid,
                null, null,
                new ClientChainConfigSyncDispatch.Dispatcher() {
                    @Override public boolean dispatch(Runnable task) { harness.queued = task; return true; }
                },
                new ClientChainConfigSyncDispatch.AcceptedPublication() {
                    @Override public void publish(int radius, int maxBlocks, int matchedCount,
                            TunnelDirectionSource source) {
                        harness.state[0] = radius;
                        harness.state[1] = maxBlocks;
                        harness.state[2] = matchedCount;
                        harness.source = source;
                        harness.publications++;
                    }
                });
        Assert.assertNotNull(harness.queued);
        return harness;
    }

    private static final class Harness {
        private final int[] state = new int[] {91, 92, 93};
        private Runnable queued;
        private int publications;
        private boolean accepted;
        private TunnelDirectionSource source = TunnelDirectionSource.HIT_FACE;
    }
}
