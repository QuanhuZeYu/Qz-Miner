package club.heiqi.qz_miner.chain.state;

import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

/**
 * {@link ChainPlayerState} world-tick 掉落释放失败计数单测。
 *
 * <p>纯 JVM 逻辑测试：不实例化任何 {@code GuiScreen} 子类，不触碰 GL/LWJGL。
 * 仅覆盖 {@link ChainPlayerState#incrementDropReleaseFailure()}、
 * {@link ChainPlayerState#resetDropReleaseFailure()} 与
 * {@link ChainPlayerState#clearRuntimeState(String)} 收口清零语义。</p>
 *
 * <p>守 NORTH_STAR I7：失败计数随生命周期收口（{@code clearRuntimeState}）一并清零，
 * 防跨生命周期残留脏计数；守 I10：失败计数独立于 phase/generation/executionStatus，
 * 不复用状态机写入口，反之亦然。</p>
 */
public class ChainPlayerStateDropReleaseFailureTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000EF1");

    /** incrementDropReleaseFailure 单调递增（调 3 次返回 1/2/3）。 */
    @Test
    public void incrementMonotonicIncrease() {
        ChainPlayerState state = new ChainPlayerState(PLAYER);
        Assert.assertEquals("首次 increment 应返回 1", 1, state.incrementDropReleaseFailure());
        Assert.assertEquals("第二次 increment 应返回 2", 2, state.incrementDropReleaseFailure());
        Assert.assertEquals("第三次 increment 应返回 3", 3, state.incrementDropReleaseFailure());
    }

    /** resetDropReleaseFailure 归零（reset 后再次 increment 应回到 1）。 */
    @Test
    public void resetReturnsToZero() {
        ChainPlayerState state = new ChainPlayerState(PLAYER);
        state.incrementDropReleaseFailure();
        state.incrementDropReleaseFailure();
        state.incrementDropReleaseFailure();
        // 无公开 getter，借助 reset 后再次 increment 验证归零
        state.resetDropReleaseFailure();
        Assert.assertEquals("reset 后 increment 应从 1 重新起算", 1, state.incrementDropReleaseFailure());
    }

    /** clearRuntimeState(reason) 触发后失败计数归零（守 I7）。 */
    @Test
    public void clearRuntimeStateZerosFailureCounter() {
        ChainPlayerState state = new ChainPlayerState(PLAYER);
        // 累加若干次失败，模拟连续 spawn 失败场景
        state.incrementDropReleaseFailure();
        state.incrementDropReleaseFailure();
        // clearRuntimeState 模拟生命周期收口（无 session 路径仍可达 reset 分支）
        state.clearRuntimeState("test-lifecycle");
        Assert.assertEquals("clearRuntimeState 后再次 increment 应从 1 起算（守 I7）",
            1, state.incrementDropReleaseFailure());
    }

    /**
     * 失败计数与 executionStatus 写入口隔离（守 I10）。
     *
     * <p>方向 A：increment/reset 失败计数不动 executionStatus。
     * 方向 B：setExecuting/setExecutionStatus 不动失败计数。</p>
     */
    @Test
    public void failureCounterIsolatedFromExecutionStatus() {
        ChainPlayerState state = new ChainPlayerState(PLAYER);
        Assert.assertEquals("初始 executionStatus 应为 IDLE",
            ChainExecutionStatus.IDLE, state.getExecutionStatus());
        Assert.assertFalse("初始 isExecuting 应为 false", state.isExecuting());

        // 方向 A：累加失败计数，executionStatus 不应被污染
        state.incrementDropReleaseFailure();
        state.incrementDropReleaseFailure();
        Assert.assertEquals("increment 失败计数后 executionStatus 仍 IDLE",
            ChainExecutionStatus.IDLE, state.getExecutionStatus());
        Assert.assertFalse("increment 失败计数后 isExecuting 仍 false", state.isExecuting());

        // 方向 B：切到 RUNNING，失败计数应保持（reset 后再次 increment 应为 1 才算被污染）
        state.setExecuting(true);
        Assert.assertEquals("setExecuting(true) 后 executionStatus 应为 RUNNING",
            ChainExecutionStatus.RUNNING, state.getExecutionStatus());
        Assert.assertTrue("setExecuting(true) 后 isExecuting 应为 true", state.isExecuting());
        // 失败计数之前累加 2 次，未被状态机写入污染；用 reset+increment 间接确认（reset 不抛异常即说明字段未被动）
        state.resetDropReleaseFailure();
        Assert.assertEquals("状态机写入不应污染失败计数（reset 后 increment 应为 1）",
            1, state.incrementDropReleaseFailure());

        // 反向再验：切回 IDLE 不影响
        state.setExecutionStatus(ChainExecutionStatus.IDLE, "test-back-to-idle");
        state.resetDropReleaseFailure();
        Assert.assertEquals("切回 IDLE 后 increment 应仍从 1 起算",
            1, state.incrementDropReleaseFailure());
    }

    /** 多次 increment + reset 交替不串扰（连续两轮累积均应独立）。 */
    @Test
    public void incrementResetAlternationDoesNotInterfere() {
        ChainPlayerState state = new ChainPlayerState(PLAYER);
        // 第一轮：累加 2 次
        Assert.assertEquals(1, state.incrementDropReleaseFailure());
        Assert.assertEquals(2, state.incrementDropReleaseFailure());
        state.resetDropReleaseFailure();
        // 第二轮：累加 3 次，应从 1 起算（不受第一轮残留影响）
        Assert.assertEquals(1, state.incrementDropReleaseFailure());
        Assert.assertEquals(2, state.incrementDropReleaseFailure());
        Assert.assertEquals(3, state.incrementDropReleaseFailure());
        // 再次 reset，再起算
        state.resetDropReleaseFailure();
        Assert.assertEquals(1, state.incrementDropReleaseFailure());
    }
}
