package club.heiqi.qz_miner.autotool;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** 原版库存事务桥的 headless 协调回归。 */
public class VanillaInventoryTransactionBridgeTest {
    @Test public void rejectsInvalidSlotsAndUnavailableContext() {
        Fixture f = new Fixture();
        Assert.assertFalse(f.bridge.beginSwap(8, 0, f.source, f.anchor, f.source));
        Assert.assertFalse(f.bridge.beginSwap(36, 0, f.source, f.anchor, f.source));
        Assert.assertFalse(f.bridge.beginSwap(9, 9, f.source, f.anchor, f.source));
        f.transport.available = false;
        Assert.assertFalse(f.bridge.beginSwap(9, 0, f.source, f.anchor, f.source));
        Assert.assertEquals(0, f.transport.clicks);
    }

    @Test public void singleFlightBindsActualActionAndIgnoresOutOfOrderConfirm() {
        Fixture f = new Fixture();
        Assert.assertTrue(f.bridge.beginSwap(12, 3, f.source, f.anchor, f.source));
        Assert.assertFalse(f.bridge.beginSwap(13, 4, new Object(), f.anchor, f.source));
        f.bridge.onClickPacket(0, 12, 3, 2, (short) 7);
        f.bridge.onConfirmTransaction(0, (short) 6, true);
        Assert.assertEquals(ToolSwapTransaction.State.WAIT_SELECT_CONFIRM, f.bridge.state());
        f.bridge.onConfirmTransaction(0, (short) 7, true);
        Assert.assertEquals(ToolSwapTransaction.State.ACTIVE, f.bridge.state());
        Assert.assertEquals(VanillaInventoryTransactionBridge.Status.ACTIVE, f.statuses.get(0));
    }

    @Test public void mismatchedPacketCannotBindTransaction() {
        Fixture f = new Fixture();
        f.bridge.beginSwap(12, 3, f.source, f.anchor, f.source);
        f.bridge.onClickPacket(1, 12, 3, 2, (short) 4);
        f.bridge.onClickPacket(0, 13, 3, 2, (short) 4);
        f.bridge.onConfirmTransaction(0, (short) 4, true);
        Assert.assertEquals(ToolSwapTransaction.State.WAIT_SELECT_CONFIRM, f.bridge.state());
    }

    @Test public void rejectedTransactionWaitsForWindowZeroItems() {
        Fixture f = activeFixture();
        Assert.assertTrue(f.bridge.requestRestore(f.source, f.anchor, null));
        f.bridge.onClickPacket(0, 12, 3, 2, (short) 8);
        f.bridge.onConfirmTransaction(0, (short) 8, false);
        Assert.assertEquals(ToolSwapTransaction.State.WAIT_RESYNC, f.bridge.state());
        f.bridge.onWindowItems(1);
        Assert.assertEquals(ToolSwapTransaction.State.WAIT_RESYNC, f.bridge.state());
        f.bridge.onWindowItems(0);
        Assert.assertEquals(ToolSwapTransaction.State.IDLE, f.bridge.state());
    }

    @Test public void restoreUsesSameModeTwoClickAndReturnsIdle() {
        Fixture f = activeFixture();
        Object desired = new Object();
        Assert.assertTrue(f.bridge.requestRestore(f.source, f.anchor, desired));
        Assert.assertEquals(2, f.transport.clicks);
        Assert.assertEquals(12, f.transport.lastSlot);
        Assert.assertEquals(3, f.transport.lastAnchor);
        f.bridge.onClickPacket(0, 12, 3, 2, (short) 9);
        f.bridge.onConfirmTransaction(0, (short) 9, true);
        Assert.assertEquals(ToolSwapTransaction.State.IDLE, f.bridge.state());
        Assert.assertSame(desired, f.bridge.latestDesired());
    }

    @Test public void timeoutPausesAndLifecycleResetClearsObserver() {
        Fixture f = new Fixture();
        f.bridge.beginSwap(12, 3, f.source, f.anchor, f.source);
        for (int i = 0; i < 40; i++) f.bridge.tickTimeout();
        Assert.assertEquals(ToolSwapTransaction.State.PAUSED, f.bridge.state());
        VanillaInventoryTransactionObserver.clickPacket(0, 12, 3, 2, (short) 11);
        VanillaInventoryTransactionObserver.confirm(0, (short) 11, true);
        Assert.assertEquals(ToolSwapTransaction.State.PAUSED, f.bridge.state());
        f.bridge.resetLifecycle();
        Assert.assertEquals(ToolSwapTransaction.State.IDLE, f.bridge.state());
        Assert.assertNull(f.bridge.latestDesired());
        Assert.assertEquals(VanillaInventoryTransactionBridge.Status.IDLE,
                f.statuses.get(f.statuses.size() - 1));
    }

    @Test public void clickFailurePausesAndReleasesObserverForAnotherBridge() {
        Fixture failed = new Fixture();
        failed.transport.failure = new IllegalStateException("click failed");
        Assert.assertFalse(failed.bridge.beginSwap(12, 3, failed.source, failed.anchor, failed.source));
        Assert.assertEquals(ToolSwapTransaction.State.PAUSED, failed.bridge.state());
        Assert.assertEquals(VanillaInventoryTransactionBridge.Status.PAUSED, failed.statuses.get(0));

        Fixture next = new Fixture();
        Assert.assertTrue(next.bridge.beginSwap(13, 4, next.source, next.anchor, next.source));
        VanillaInventoryTransactionObserver.clickPacket(0, 13, 4, 2, (short) 12);
        VanillaInventoryTransactionObserver.confirm(0, (short) 12, true);
        Assert.assertEquals(ToolSwapTransaction.State.ACTIVE, next.bridge.state());
    }

    private static Fixture activeFixture() {
        Fixture f = new Fixture();
        f.bridge.beginSwap(12, 3, f.source, f.anchor, f.source);
        f.bridge.onClickPacket(0, 12, 3, 2, (short) 7);
        f.bridge.onConfirmTransaction(0, (short) 7, true);
        return f;
    }
    private static final class Fixture {
        final Object source = new Object();
        final Object anchor = new Object();
        final FakeTransport transport = new FakeTransport();
        final List<VanillaInventoryTransactionBridge.Status> statuses = new ArrayList<VanillaInventoryTransactionBridge.Status>();
        final VanillaInventoryTransactionBridge<Object> bridge = new VanillaInventoryTransactionBridge<Object>(
                transport, new VanillaInventoryTransactionBridge.Listener() {
                    @Override public void onStatusChanged(VanillaInventoryTransactionBridge.Status status) { statuses.add(status); }
                });
    }
    private static final class FakeTransport implements VanillaInventoryTransactionBridge.ClickTransport {
        boolean available = true; int clicks; int lastSlot; int lastAnchor; RuntimeException failure;
        @Override public boolean canClick() { return available; }
        @Override public void click(int slot, int anchor) {
            clicks++; lastSlot = slot; lastAnchor = anchor;
            if (failure != null) throw failure;
        }
    }
}
