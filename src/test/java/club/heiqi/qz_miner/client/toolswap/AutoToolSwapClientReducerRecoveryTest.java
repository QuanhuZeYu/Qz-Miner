package club.heiqi.qz_miner.client.toolswap;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Assert;
import org.junit.Test;

/** 传输失败后必须等待物理松键，再允许建立全新 round。 */
public class AutoToolSwapClientReducerRecoveryTest {

    @Test
    public void orphanedRoundRecoversOnlyAfterKeyRelease() {
        final AtomicLong nonce = new AtomicLong(1L);
        AutoToolSwapClientReducer reducer = new AutoToolSwapClientReducer(true,
                new AutoToolSwapClientReducer.NonceAllocator() {
                    @Override
                    public long allocate() { return nonce.getAndIncrement(); }
                });
        ToolSwapLightContext context = new ToolSwapLightContext(0L, true, false, false, true, 0);

        List<AutoToolSwapClientReducer.Effect> first = reducer.reduce(
                new AutoToolSwapClientReducer.KeyStateEvent(true, context));
        Assert.assertEquals(1, first.size());
        Assert.assertEquals(1L, first.get(0).clientNonce());
        reducer.reduce(new AutoToolSwapClientReducer.EffectResultEvent(first.get(0), false));
        Assert.assertTrue(reducer.isOrphaned());
        Assert.assertTrue(reducer.reduce(new AutoToolSwapClientReducer.KeyStateEvent(true, context)).isEmpty());

        reducer.reduce(new AutoToolSwapClientReducer.KeyStateEvent(false, context));
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, reducer.state());
        List<AutoToolSwapClientReducer.Effect> second = reducer.reduce(
                new AutoToolSwapClientReducer.KeyStateEvent(true, context));
        Assert.assertEquals(1, second.size());
        Assert.assertEquals(2L, second.get(0).clientNonce());
    }
}
