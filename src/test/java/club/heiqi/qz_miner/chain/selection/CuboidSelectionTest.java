package club.heiqi.qz_miner.chain.selection;

import org.junit.Assert;
import org.junit.Test;

/** 双点归一化、体积上限和拒绝原子性。 */
public class CuboidSelectionTest {

    @Test
    public void boundsAreInclusiveNormalizedAndOverflowSafe() {
        CuboidBounds bounds = CuboidBounds.between(7, 3, 9, -2, 1, 7, 2);
        Assert.assertEquals(1, bounds.getMinX());
        Assert.assertEquals(7, bounds.getMinY());
        Assert.assertEquals(-2, bounds.getMinZ());
        Assert.assertEquals(3, bounds.getMaxX());
        Assert.assertEquals(9, bounds.getMaxY());
        Assert.assertEquals(2, bounds.getMaxZ());
        Assert.assertEquals(45L, bounds.volume());
        Assert.assertTrue(bounds.fitsWithin(45));
        Assert.assertFalse(bounds.fitsWithin(44));

        CuboidBounds extreme = CuboidBounds.between(0,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        Assert.assertEquals(Long.MAX_VALUE, extreme.volume());
        Assert.assertFalse(extreme.fitsWithin(Integer.MAX_VALUE));
    }

    @Test
    public void oversizedReplacementKeepsBothOldPointsAndRevision() {
        CuboidSelection selection = CuboidSelection.empty()
                .select(1, 0, 0, 0, 0, 8).getSelection()
                .select(2, 0, 1, 1, 1, 8).getSelection();
        Assert.assertEquals(2L, selection.getRevision());
        CuboidSelection.Update rejected = selection.select(2, 0, 100, 100, 100, 8);
        Assert.assertFalse(rejected.isAccepted());
        Assert.assertSame(selection, rejected.getSelection());
        Assert.assertEquals(1, rejected.getSelection().getPoint2().getX());
        Assert.assertEquals(2L, rejected.getSelection().getRevision());
    }

    @Test
    public void pointsCanBeSelectedAndOverwrittenInEitherOrder() {
        CuboidSelection selection = CuboidSelection.empty()
                .select(2, -1, 4, 5, 6, 64).getSelection()
                .select(1, -1, 3, 4, 5, 64).getSelection();
        Assert.assertTrue(selection.isComplete());
        Assert.assertEquals(8L, selection.bounds().volume());
        CuboidSelection.Update overwritten = selection.select(1, -1, 4, 5, 6, 64);
        Assert.assertTrue(overwritten.isAccepted());
        Assert.assertEquals(1L, overwritten.getSelection().bounds().volume());
        Assert.assertEquals(3L, overwritten.getSelection().getRevision());
    }

    @Test
    public void authorityClearKeepsRevisionMonotonic() {
        CuboidSelection selected = CuboidSelection.empty()
                .select(1, 0, 1, 2, 3, 8).getSelection();
        CuboidSelection cleared = selected.clearForAuthority();
        Assert.assertEquals(2L, cleared.getRevision());
        Assert.assertNull(cleared.getPoint1());
        Assert.assertNull(cleared.getPoint2());
        Assert.assertEquals(3L, cleared.select(2, 0, 4, 5, 6, 8)
                .getSelection().getRevision());
    }
}
