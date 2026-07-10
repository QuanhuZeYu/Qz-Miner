package club.heiqi.qz_miner.client;

import org.junit.Assert;
import org.junit.Test;

/** 客户端配置同步必须先投递再触碰状态。 */
public class ClientChainConfigSyncDispatchTest {

    @Test
    public void publicationRunsOnlyInsideDispatchedTaskWithCapturedValues() {
        final Runnable[] queued = new Runnable[1];
        final int[] published = new int[3];

        ClientChainConfigSyncDispatch.dispatch(12, 345, 67,
                new ClientChainConfigSyncDispatch.Dispatcher() {
                    @Override
                    public void dispatch(Runnable task) {
                        queued[0] = task;
                    }
                },
                new ClientChainConfigSyncDispatch.Publication() {
                    @Override
                    public void publish(int radius, int maxBlocks, int matchedCount) {
                        published[0] = radius;
                        published[1] = maxBlocks;
                        published[2] = matchedCount;
                    }
                });

        Assert.assertNotNull(queued[0]);
        Assert.assertArrayEquals("dispatcher acceptance must not publish state", new int[] {0, 0, 0}, published);
        queued[0].run();
        Assert.assertArrayEquals(new int[] {12, 345, 67}, published);
    }
}
