package club.heiqi.qz_miner.chain.state;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.config.CommittedSnapshot;
import club.heiqi.qz_miner.config.CommittedSnapshotTestFactory;
import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;

/** 客户端对象组确认的提交快照、乱序、内容和重连回归。 */
public class ChainClientObjectGroupSyncTest {

    @Test
    public void ackForRevisionNUsesNRequestWhenGlobalConfigurationHasMovedOn() {
        ChainClientState state = new ChainClientState();
        CommittedSnapshot n = snapshot(5L, rules("logs", "minecraft:log@*"));
        CommittedSnapshot nPlusOne = snapshot(6L, rules("ores", "minecraft:iron_ore@0"));
        state.beginObjectGroupSync(1L, n);
        state.registerObjectGroupRequest(1L, nPlusOne);

        Assert.assertTrue(state.applyObjectGroupSyncResult(1L, 5L, 5L, true, 1));
        Assert.assertSame(n.snapshot.objectGroups, state.getServerObjectGroups());
        Assert.assertNotSame(nPlusOne.snapshot.objectGroups, state.getServerObjectGroups());
    }

    @Test
    public void oldAckIsIgnoredAfterNewerAck() {
        ChainClientState state = new ChainClientState();
        CommittedSnapshot n = snapshot(5L, rules("logs", "minecraft:log@*"));
        CommittedSnapshot nPlusOne = snapshot(6L, rules("ores", "minecraft:iron_ore@0"));
        state.beginObjectGroupSync(1L, n);
        state.registerObjectGroupRequest(1L, nPlusOne);

        Assert.assertTrue(state.applyObjectGroupSyncResult(1L, 6L, 6L, true, 1));
        Assert.assertFalse(state.applyObjectGroupSyncResult(1L, 5L, 5L, true, 1));
        Assert.assertSame(nPlusOne.snapshot.objectGroups, state.getServerObjectGroups());
    }

    @Test
    public void sameGroupCountWithDifferentRulesCannotBeConfirmedFromCurrent() {
        ChainClientState state = new ChainClientState();
        CommittedSnapshot requested = snapshot(5L, rules("requested", "minecraft:log@0"));
        CommittedSnapshot differentCurrent = snapshot(6L, rules("current", "minecraft:stone@0"));
        state.beginObjectGroupSync(1L, requested);

        Assert.assertFalse(state.applyObjectGroupSyncResult(1L, 5L, 5L, true, 2));
        Assert.assertTrue(state.applyObjectGroupSyncResult(1L, 5L, 5L, true, 1));
        Assert.assertSame(requested.snapshot.objectGroups, state.getServerObjectGroups());
        Assert.assertNotSame(differentCurrent.snapshot.objectGroups, state.getServerObjectGroups());
    }

    @Test
    public void reconnectClearsPendingAndRejectsOldConnectionAck() {
        ChainClientState state = new ChainClientState();
        CommittedSnapshot old = snapshot(5L, rules("old", "minecraft:log@*"));
        CommittedSnapshot newer = snapshot(6L, rules("new", "minecraft:stone@0"));
        state.beginObjectGroupSync(1L, old);
        state.clearObjectGroupSyncPending();
        state.beginObjectGroupSync(2L, newer);

        Assert.assertFalse(state.applyObjectGroupSyncResult(1L, 5L, 5L, true, 1));
        Assert.assertTrue(state.applyObjectGroupSyncResult(2L, 6L, 6L, true, 1));
        Assert.assertSame(newer.snapshot.objectGroups, state.getServerObjectGroups());
    }

    private static CommittedSnapshot snapshot(long epoch, ObjectGroupRuleSet rules) {
        return CommittedSnapshotTestFactory.create(epoch, rules);
    }

    private static ObjectGroupRuleSet rules(String id, String selector) {
        return new ObjectGroupRuleSet(Collections.singletonList(new ObjectGroup(
                id, Collections.singletonList(club.heiqi.qz_miner.objectgroup.ObjectGroupMode.CHAIN_BASE), 1L,
                Arrays.asList(ObjectGroupParser.parseSelector(selector)))));
    }
}
