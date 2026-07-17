package club.heiqi.qz_miner.chain.state;

import java.util.Collections;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;

/** 玩家状态移除时对象组规则清理回归。 */
public class ChainPlayerObjectGroupLifecycleTest {

    @Test
    public void explicitPlayerStateCleanupClearsRulesAndRevision() {
        ChainPlayerState state = new ChainPlayerState(UUID.randomUUID());
        ObjectGroupRuleSet rules = new ObjectGroupRuleSet(Collections.singletonList(new ObjectGroup(
                "logs", Collections.singletonList(club.heiqi.qz_miner.objectgroup.ObjectGroupMode.CHAIN_BASE), 1L,
                Collections.singletonList(ObjectGroupParser.parseSelector("minecraft:log@*")))));
        state.setObjectGroupRules(rules, 9L);
        state.clearObjectGroupRules();
        Assert.assertTrue(state.getObjectGroupRules().groups().isEmpty());
        Assert.assertEquals(0L, state.getObjectGroupRevision());
    }
}
