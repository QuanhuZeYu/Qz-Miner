package club.heiqi.qz_miner.thread;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

/**
 * ServerMainThreadDispatcher keyed drain 不可重入 guard 的纯 JVM 测试。
 *
 * <p>不构造 FML 事件；直接测 package-private {@link ServerMainThreadDispatcher#drainKeyedLaneGuarded()}。</p>
 */
public class ServerMainThreadDispatcherKeyedReentryTest {

    @Test
    public void nestedKeyedDrainSkipsButOuterBudgetIsRespected() {
        KeyedLatestTaskLane<Object> lane = ServerMainThreadDispatcher.keyedLaneForTests();
        lane.stop();
        lane.start();

        final AtomicInteger executed = new AtomicInteger();
        final AtomicInteger nestedDrained = new AtomicInteger(-1);
        for (int i = 0; i < 65; i++) {
            final int key = i;
            Assert.assertTrue(lane.submit(Integer.valueOf(key), new Runnable() {
                @Override
                public void run() {
                    executed.incrementAndGet();
                    if (key == 0) {
                        // 外层 START drain 执行中嵌套再入 keyed drain
                        nestedDrained.set(ServerMainThreadDispatcher.drainKeyedLaneGuarded());
                    }
                }
            }));
        }
        Assert.assertEquals(65, lane.pendingCount());

        int outer = ServerMainThreadDispatcher.drainKeyedLaneGuarded();
        Assert.assertEquals("single outer drain must honor budget 64", 64, outer);
        Assert.assertEquals(0, nestedDrained.get());
        Assert.assertEquals(64, executed.get());
        Assert.assertEquals(1, lane.pendingCount());

        int second = ServerMainThreadDispatcher.drainKeyedLaneGuarded();
        Assert.assertEquals(1, second);
        Assert.assertEquals(65, executed.get());
        Assert.assertEquals(0, lane.pendingCount());
        Assert.assertFalse(ServerMainThreadDispatcher.keyedDrainActiveForTests());
    }
}
