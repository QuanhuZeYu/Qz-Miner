package club.heiqi.qz_miner.chain.planner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.selection.CuboidBounds;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickStage;

/** Cuboid scanner 的稳定顺序、让出恢复与框外 origin 合同。 */
public class CuboidScanTraverserTest {

    @Test
    public void tinySlicesVisitEveryInclusiveCoordinateExactlyOnce() {
        CuboidBounds bounds = CuboidBounds.between(0, 10, 20, 30, 11, 21, 31);
        ChainSearchContext context = context(8);
        CuboidScanTraverser traverser = new CuboidScanTraverser(bounds);
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        traverser.seed(context);
        TraversalStepResult result = TraversalStepResult.CONTINUE;
        for (int slice = 0; slice < 100 && result != TraversalStepResult.COMPLETED; slice++) {
            result = traverser.step(context, new SliceControl(4), target -> true, targets::add);
        }
        Assert.assertEquals(TraversalStepResult.COMPLETED, result);
        Assert.assertEquals(8, targets.size());
        Assert.assertEquals(8, new HashSet<ChainTarget>(targets).size());
        Assert.assertEquals(new ChainTarget(10, 20, 30), targets.get(0));
        Assert.assertEquals(new ChainTarget(11, 21, 31), targets.get(7));
        Assert.assertFalse(bounds.equals(CuboidBounds.between(0, -100, -100, -100, -100, -100, -100)));
    }

    private static ChainSearchContext context(int maxTargets) {
        ChainSearchContext context = new ChainSearchContext(
                null, new ChainTarget(-100, -100, -100), null, 0,
                TileIdentityToken.absent(), null, ChainSubMode.AREA_CUBOID_CLEAR, 1, maxTargets,
                new ConcurrentLinkedQueue<ChainTarget>(), new ConcurrentLinkedQueue<ChainTarget>(),
                new HashSet<ChainTarget>(), null, target -> false);
        context.setCandidateFilter(target -> true);
        return context;
    }

    private static final class SliceControl implements ParallelTickControl {
        private int remaining;
        private SliceControl(int remaining) { this.remaining = remaining; }
        @Override public long getTickId() { return 1L; }
        @Override public ParallelTickStage getStage() { return ParallelTickStage.SERVER_PRE; }
        @Override public boolean isWindowOpen() { return true; }
        @Override public boolean isCancelRequested() { return false; }
        @Override public boolean shouldYield() { return remaining-- <= 0; }
        @Override public long getElapsedNanoTime() { return 0L; }
        @Override public String getCancelReason() { return ""; }
    }
}
