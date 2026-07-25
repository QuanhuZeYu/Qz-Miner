package club.heiqi.qz_miner.objectgroup;

import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

/** 完整非负 int metadata 域的并集、匹配、交集与不可变合同。 */
public class ObjectGroupMetadataDomainTest {

    @Test
    public void finiteDomainsUnionWithoutMaskingOrOverflow() {
        ObjectGroupMetadataDomain left = ObjectGroupMetadataDomain.from(
                ObjectGroupSelector.set("test:block", Arrays.asList(16, 24902, Integer.MAX_VALUE)));
        ObjectGroupMetadataDomain right = ObjectGroupMetadataDomain.from(
                ObjectGroupSelector.set("test:block", Arrays.asList(65535, 16777216, Integer.MAX_VALUE)));

        ObjectGroupMetadataDomain union = left.union(right);

        Assert.assertEquals(Arrays.asList(16, 24902, 65535, 16777216, Integer.MAX_VALUE),
                union.metadata());
        Assert.assertTrue(union.matches(16));
        Assert.assertTrue(union.matches(16777216));
        Assert.assertTrue(union.matches(Integer.MAX_VALUE));
        Assert.assertFalse(union.matches(-1));
        Assert.assertFalse(union.matches(17));
        Assert.assertFalse(union.isWildcard());
    }

    @Test
    public void wildcardMatchesAndIntersectsTheEntireNonNegativeIntDomain() {
        ObjectGroupMetadataDomain wildcard = ObjectGroupMetadataDomain.from(
                ObjectGroupSelector.wildcard("test:block"));
        ObjectGroupMetadataDomain maximum = ObjectGroupMetadataDomain.from(
                ObjectGroupSelector.single("test:block", Integer.MAX_VALUE));

        Assert.assertTrue(wildcard.isWildcard());
        Assert.assertTrue(wildcard.matches(0));
        Assert.assertTrue(wildcard.matches(Integer.MAX_VALUE));
        Assert.assertFalse(wildcard.matches(-1));
        Assert.assertTrue(wildcard.intersects(maximum));
        Assert.assertTrue(maximum.intersects(wildcard));
        Assert.assertSame(wildcard, wildcard.union(maximum));
    }

    @Test
    public void finiteIntersectionUsesExactFullIntValues() {
        ObjectGroupMetadataDomain first = ObjectGroupMetadataDomain.from(
                ObjectGroupSelector.set("test:block", Arrays.asList(16, 65535, Integer.MAX_VALUE)));
        ObjectGroupMetadataDomain overlap = ObjectGroupMetadataDomain.from(
                ObjectGroupSelector.set("test:block", Arrays.asList(24902, Integer.MAX_VALUE)));
        ObjectGroupMetadataDomain disjoint = ObjectGroupMetadataDomain.from(
                ObjectGroupSelector.set("test:block", Arrays.asList(0, 16777216)));

        Assert.assertTrue(first.intersects(overlap));
        Assert.assertFalse(first.intersects(disjoint));
        Assert.assertFalse(first.intersects(null));
        try {
            first.metadata().add(Integer.valueOf(1));
            Assert.fail("metadata snapshot must be immutable");
        } catch (UnsupportedOperationException expected) {
            // 合同断言
        }
    }
}
