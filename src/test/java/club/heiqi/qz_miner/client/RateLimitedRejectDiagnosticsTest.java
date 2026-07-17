package club.heiqi.qz_miner.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/** 限频拒绝诊断聚合器纯 JVM 测试。 */
public class RateLimitedRejectDiagnosticsTest {

    private RateLimitedRejectDiagnostics diag;

    @Before
    public void setUp() {
        diag = new RateLimitedRejectDiagnostics(TimeUnit.SECONDS.toNanos(10L));
    }

    @Test
    public void concurrentWindowEmitsSingleLogWithPositiveBatch() throws Exception {
        final List<Long> batches = Collections.synchronizedList(new ArrayList<Long>());
        final AtomicInteger logCalls = new AtomicInteger();
        final int threads = 16;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        diag.note(1_000_000L, new RateLimitedRejectDiagnostics.BatchLogger() {
                            @Override
                            public void log(long batchCount) {
                                logCalls.incrementAndGet();
                                batches.add(Long.valueOf(batchCount));
                            }
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }
            }, "reject-diag-" + i).start();
        }
        start.countDown();
        Assert.assertTrue(done.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(1, logCalls.get());
        Assert.assertEquals(1, batches.size());
        Assert.assertTrue(batches.get(0).longValue() > 0L);
        Assert.assertTrue(batches.get(0).longValue() <= threads);
        Assert.assertTrue(diag.pendingCountForTests() >= 0L);
    }

    @Test
    public void subsequentWindowCanLogAgain() {
        final AtomicInteger logs = new AtomicInteger();
        long interval = TimeUnit.SECONDS.toNanos(10L);
        diag.note(1L, new RateLimitedRejectDiagnostics.BatchLogger() {
            @Override
            public void log(long batchCount) {
                logs.incrementAndGet();
            }
        });
        Assert.assertEquals(1, logs.get());

        diag.note(interval / 2L, new RateLimitedRejectDiagnostics.BatchLogger() {
            @Override
            public void log(long batchCount) {
                logs.incrementAndGet();
            }
        });
        Assert.assertEquals(1, logs.get());

        diag.note(interval + 2L, new RateLimitedRejectDiagnostics.BatchLogger() {
            @Override
            public void log(long batchCount) {
                logs.incrementAndGet();
                Assert.assertTrue(batchCount > 0L);
            }
        });
        Assert.assertEquals(2, logs.get());
    }
}
