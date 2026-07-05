package club.heiqi.qz_miner.chain.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.event.PlayerStateEvent;

/**
 * {@link ChainLifecycleBridge} 单测。
 *
 * <p>纯 JVM 逻辑测试：通过 {@link ChainLifecycleBridge#handlePlayerLifecycle} 包级可测接缝驱动，
 * 避免实例化 {@link net.minecraft.entity.player.EntityPlayer}（守传感层 §2.2，玩家类构造需 World/GL）。</p>
 *
 * <p>F.1 W1（forced=true）+ F.2 S1（removeSlot 分流）+ 5 类 reason 全覆盖。</p>
 */
public class ChainLifecycleBridgeTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000AB");

    /** 构造 bridge + 捕获器（订阅 LifecycleCleanup 捕获 bridge publish 的事件）。 */
    private static Harness newHarness() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainLifecycleBridge bridge = new ChainLifecycleBridge(bus);
        List<LifecycleCleanup> captured = new ArrayList<LifecycleCleanup>();
        bus.subscribe(LifecycleCleanup.class, captured::add);
        return new Harness(bus, bridge, captured);
    }

    private static void fire(Harness h, PlayerStateEvent.Reason reason) {
        h.bridge.handlePlayerLifecycle(PLAYER, reason);
        h.bus.drain();
    }

    private static final class Harness {
        final ChainEventBus bus;
        final ChainLifecycleBridge bridge;
        final List<LifecycleCleanup> captured;

        Harness(ChainEventBus bus, ChainLifecycleBridge bridge, List<LifecycleCleanup> captured) {
            this.bus = bus;
            this.bridge = bridge;
            this.captured = captured;
        }
    }

    /** LOGIN 不 publish（玩家刚加入，无活跃连锁需清理）。 */
    @Test
    public void loginDoesNotPublish() {
        Harness h = newHarness();
        fire(h, PlayerStateEvent.Reason.LOGIN);
        Assert.assertTrue("LOGIN 不应 publish LifecycleCleanup", h.captured.isEmpty());
    }

    /** LOGOUT → publish LifecycleCleanup(forced=true, removeSlot=true, reason="player-logout")。 */
    @Test
    public void logoutPublishesForcedRemove() {
        Harness h = newHarness();
        fire(h, PlayerStateEvent.Reason.LOGOUT);
        Assert.assertEquals("LOGOUT 应 publish 一条 LifecycleCleanup", 1, h.captured.size());
        LifecycleCleanup e = h.captured.get(0);
        Assert.assertEquals(PLAYER, e.getPlayerUUID());
        Assert.assertTrue("F.1 W1：LOGOUT 应 forced=true 豁免 genCheck", e.isForced());
        Assert.assertTrue("F.2 S1：LOGOUT 应 removeSlot=true 删槽防泄漏", e.isRemoveSlot());
        Assert.assertEquals("player-logout", e.getReason());
    }

    /** RESPAWN → publish LifecycleCleanup(forced=true, removeSlot=false, reason="player-respawn")。 */
    @Test
    public void respawnPublishesForcedKeepSlot() {
        Harness h = newHarness();
        fire(h, PlayerStateEvent.Reason.RESPAWN);
        Assert.assertEquals(1, h.captured.size());
        LifecycleCleanup e = h.captured.get(0);
        Assert.assertTrue("F.1 W1：RESPAWN 应 forced=true", e.isForced());
        Assert.assertFalse("F.2 S1：RESPAWN 应 removeSlot=false 保 gen 单调", e.isRemoveSlot());
        Assert.assertEquals("player-respawn", e.getReason());
    }

    /** DIMENSION_CHANGE → publish LifecycleCleanup(forced=true, removeSlot=false)。 */
    @Test
    public void dimensionChangePublishesForcedKeepSlot() {
        Harness h = newHarness();
        fire(h, PlayerStateEvent.Reason.DIMENSION_CHANGE);
        Assert.assertEquals(1, h.captured.size());
        LifecycleCleanup e = h.captured.get(0);
        Assert.assertTrue(e.isForced());
        Assert.assertFalse(e.isRemoveSlot());
        Assert.assertEquals("player-dimension-change", e.getReason());
    }

    /** CLONE → publish LifecycleCleanup(forced=true, removeSlot=false)。 */
    @Test
    public void clonePublishesForcedKeepSlot() {
        Harness h = newHarness();
        fire(h, PlayerStateEvent.Reason.CLONE);
        Assert.assertEquals(1, h.captured.size());
        LifecycleCleanup e = h.captured.get(0);
        Assert.assertTrue(e.isForced());
        Assert.assertFalse(e.isRemoveSlot());
        Assert.assertEquals("player-clone", e.getReason());
    }
}
