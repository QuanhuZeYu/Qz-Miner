package club.heiqi.qz_miner.chain.state;

import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;

/** 客户端对象组确认的 epoch、乱序和重连水位回归。 */
public class ChainClientObjectGroupSyncTest {

    @Test
    public void rev5SuccessCannotBeReversedByLateOldReject() {
        ChainClientState state = new ChainClientState();
        ObjectGroupRuleSet local = rules("logs");
        state.beginObjectGroupSync(5L);

        Assert.assertTrue(state.applyObjectGroupSyncResult(local, 5L, 5L, 5L, true, 1));
        Assert.assertFalse(state.applyObjectGroupSyncResult(local, 5L, 4L, 4L, false, 0));
        Assert.assertTrue(state.isObjectGroupSyncAccepted());
        Assert.assertEquals(5L, state.getServerObjectGroupRevision());
    }

    @Test
    public void outOfOrderRejectThenSuccessUsesAuthoritativeRevisionOrder() {
        ChainClientState state = new ChainClientState();
        ObjectGroupRuleSet local = rules("logs");
        state.beginObjectGroupSync(5L);

        Assert.assertTrue(state.applyObjectGroupSyncResult(local, 5L, 5L, 4L, false, 0));
        Assert.assertTrue(state.applyObjectGroupSyncResult(local, 5L, 5L, 5L, true, 1));
        Assert.assertTrue(state.isObjectGroupSyncAccepted());
        Assert.assertEquals(5L, state.getServerObjectGroupRevision());

        Assert.assertFalse(state.applyObjectGroupSyncResult(local, 5L, 5L, 4L, false, 0));
        Assert.assertTrue(state.isObjectGroupSyncAccepted());
    }

    @Test
    public void reconnectResetsResultWatermarkButOldSubmissionEpochIsIgnored() {
        ChainClientState state = new ChainClientState();
        ObjectGroupRuleSet local = rules("logs");
        state.beginObjectGroupSync(5L);
        Assert.assertTrue(state.applyObjectGroupSyncResult(local, 5L, 5L, 5L, true, 1));

        // 新连接可以复用本地 epoch，但不应沿用旧连接的确认状态。
        state.beginObjectGroupSync(5L);
        Assert.assertFalse(state.isObjectGroupSyncAccepted());
        Assert.assertFalse(state.applyObjectGroupSyncResult(local, 5L, 4L, 4L, false, 0));
        Assert.assertTrue(state.applyObjectGroupSyncResult(local, 5L, 5L, 5L, true, 1));
        Assert.assertTrue(state.isObjectGroupSyncAccepted());
    }

    @Test
    public void acceptedResultRequiresLocalGroupCountMatch() {
        ChainClientState state = new ChainClientState();
        state.beginObjectGroupSync(5L);
        Assert.assertFalse(state.applyObjectGroupSyncResult(rules("logs"), 5L, 5L, 5L, true, 2));
        Assert.assertFalse(state.isObjectGroupSyncAccepted());
    }

    private static ObjectGroupRuleSet rules(String id) {
        return new ObjectGroupRuleSet(Collections.singletonList(new ObjectGroup(
                id, Collections.singletonList(ObjectGroupParser.parseSelector("minecraft:log@*")))));
    }
}
