package club.heiqi.qz_miner.chain.state;

import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

/**
 * {@link ChainPlayerState} 起点方块掉落捕获 armed 标志单测。
 *
 * <p>纯 JVM 逻辑测试：不实例化任何 {@code GuiScreen} 子类，不触碰 GL/LWJGL。
 * 仅覆盖 {@link ChainPlayerState#armSeedDropCapture(long)} 与
 * {@link ChainPlayerState#consumeSeedDropCaptureIfArmed(long)} 的一次性 + 同 tick 戳语义。</p>
 *
 * <p>守 NORTH_STAR I10：armed 标志独立于 phase/generation/executionStatus，
 * 不复用 {@link ChainPlayerState#setExecuting(boolean)} 写入口，本测验证其不污染执行状态。</p>
 */
public class ChainPlayerStateSeedCaptureTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000EE");

    /** arm 后同 tick consume 返回 true 并清零（一次性正常消费）。 */
    @Test
    public void armThenConsumeSameTickReturnsTrueAndClears() {
        ChainPlayerState state = new ChainPlayerState(PLAYER);
        Assert.assertFalse("consume 前 armed 应为 false", state.consumeSeedDropCaptureIfArmed(42L));

        state.armSeedDropCapture(42L);
        Assert.assertTrue("arm 后同 tick consume 应返回 true", state.consumeSeedDropCaptureIfArmed(42L));
        // 二次同 tick consume 应返回 false（已一次性清零）
        Assert.assertFalse("二次 consume 同 tick 应返回 false", state.consumeSeedDropCaptureIfArmed(42L));
    }

    /** armed 跨 tick 戳不同返回 false（陈旧标志自然失效，不主动清零）。 */
    @Test
    public void consumeDifferentTickReturnsFalse() {
        ChainPlayerState state = new ChainPlayerState(PLAYER);
        state.armSeedDropCapture(42L);
        // 下一个 tick 戳不同 → 返回 false（陈旧标志兜底失效）
        Assert.assertFalse("跨 tick consume 应返回 false", state.consumeSeedDropCaptureIfArmed(43L));
        // 仍是同 tick 戳验，再回到 42 应仍能消费一次（未清零，陈旧兜底而非主动清）
        Assert.assertTrue("回到 arm tick 戳仍能消费一次", state.consumeSeedDropCaptureIfArmed(42L));
        Assert.assertFalse("消费后再次 false", state.consumeSeedDropCaptureIfArmed(42L));
    }

    /** 未 arm 直接 consume 返回 false（不动状态）。 */
    @Test
    public void consumeWithoutArmReturnsFalse() {
        ChainPlayerState state = new ChainPlayerState(PLAYER);
        Assert.assertFalse("未 arm consume 应 false", state.consumeSeedDropCaptureIfArmed(42L));
        Assert.assertFalse("再次未 arm consume 仍 false", state.consumeSeedDropCaptureIfArmed(42L));
    }

    /** arm 不污染 executionStatus（I10 状态机唯一写权威不受影响）。 */
    @Test
    public void armDoesNotTouchExecutionStatus() {
        ChainPlayerState state = new ChainPlayerState(PLAYER);
        Assert.assertEquals("构造后 executionStatus 应为 IDLE", ChainExecutionStatus.IDLE, state.getExecutionStatus());
        Assert.assertFalse("构造后 isExecuting 应为 false", state.isExecuting());

        state.armSeedDropCapture(42L);
        Assert.assertEquals("arm 后 executionStatus 仍 IDLE", ChainExecutionStatus.IDLE, state.getExecutionStatus());
        Assert.assertFalse("arm 后 isExecuting 仍 false", state.isExecuting());

        state.consumeSeedDropCaptureIfArmed(42L);
        Assert.assertEquals("consume 后 executionStatus 仍 IDLE", ChainExecutionStatus.IDLE, state.getExecutionStatus());
        Assert.assertFalse("consume 后 isExecuting 仍 false", state.isExecuting());
    }
}