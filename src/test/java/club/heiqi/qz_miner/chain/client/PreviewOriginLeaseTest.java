package club.heiqi.qz_miner.chain.client;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/** 本地预览 origin 租约的纯 JVM 状态序列测试。 */
public class PreviewOriginLeaseTest {

    @Test
    public void untriggeredLeaseDoesNotLock() {
        PreviewOriginLease lease = new PreviewOriginLease();
        Assert.assertFalse(lease.shouldLock(ChainPhase.ARMED, 4));
        Assert.assertFalse(lease.shouldLock(ChainPhase.IDLE, 5));
    }

    @Test
    public void armedOrIdleTriggerLocksUntilLateActiveRoundTerminates() {
        for (ChainPhase triggerPhase : new ChainPhase[] {ChainPhase.ARMED, ChainPhase.IDLE}) {
            PreviewOriginLease lease = new PreviewOriginLease();
            lease.acquire(triggerPhase, 7);
            Assert.assertTrue(lease.shouldLock(triggerPhase, 7));
            Assert.assertTrue(lease.shouldLock(ChainPhase.PLANNING, 8));
            Assert.assertTrue(lease.shouldLock(ChainPhase.RUNNING, 8));
            Assert.assertTrue(lease.shouldLock(ChainPhase.FINISHING, 8));
            Assert.assertFalse(lease.shouldLock(ChainPhase.IDLE, 8));
            Assert.assertFalse(lease.isActive());
        }
    }

    @Test
    public void triggerDuringVisibleActivePhaseReleasesOnSameGenerationTerminal() {
        for (ChainPhase active : new ChainPhase[] {
                ChainPhase.PLANNING, ChainPhase.RUNNING, ChainPhase.FINISHING}) {
            PreviewOriginLease lease = new PreviewOriginLease();
            lease.acquire(active, 12);
            Assert.assertTrue(lease.shouldLock(active, 12));
            Assert.assertFalse(lease.shouldLock(ChainPhase.ARMED, 12));
            Assert.assertFalse(lease.isActive());
        }
    }

    @Test
    public void advancedTerminalReleasesWhenActiveSnapshotWasMissed() {
        PreviewOriginLease idleTerminal = new PreviewOriginLease();
        idleTerminal.acquire(ChainPhase.ARMED, 20);
        Assert.assertTrue(idleTerminal.shouldLock(ChainPhase.IDLE, 20));
        Assert.assertFalse(idleTerminal.shouldLock(ChainPhase.IDLE, 21));

        PreviewOriginLease armedTerminal = new PreviewOriginLease();
        armedTerminal.acquire(ChainPhase.IDLE, 30);
        Assert.assertTrue(armedTerminal.shouldLock(ChainPhase.ARMED, 30));
        Assert.assertFalse(armedTerminal.shouldLock(ChainPhase.ARMED, 31));
    }

    @Test
    public void resetAndNextRoundCanLeaseAnotherOrigin() {
        PreviewOriginLease lease = new PreviewOriginLease();
        lease.acquire(ChainPhase.ARMED, 40);
        Assert.assertTrue(lease.shouldLock(ChainPhase.ARMED, 40));
        lease.reset();
        Assert.assertFalse(lease.shouldLock(ChainPhase.ARMED, 40));

        lease.acquire(ChainPhase.ARMED, 41);
        Assert.assertTrue(lease.shouldLock(ChainPhase.PLANNING, 42));
        Assert.assertFalse(lease.shouldLock(ChainPhase.IDLE, 42));
    }
}
