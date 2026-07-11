package club.heiqi.qz_miner.objectgroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/** 对象组 selector、优先级和不可变快照测试。 */
public class ObjectGroupParserTest {

    @Test
    public void parsesAllSelectorFormsAndCanonicalizesSetOrder() {
        Assert.assertEquals("minecraft:log@0", ObjectGroupParser.parseSelector("minecraft:log@0").canonical());
        Assert.assertEquals("minecraft:log@*", ObjectGroupParser.parseSelector("minecraft:log@*").canonical());
        Assert.assertEquals("minecraft:log@[0,4,8,12]",
                ObjectGroupParser.parseSelector("minecraft:log@[12,0,8,4]").canonical());
    }

    @Test
    public void exactBeatsSetBeatsWildcardAndSameLevelKeepsOrder() {
        ObjectGroupRuleSet rules = new ObjectGroupRuleSet(Arrays.asList(
                group("wild", "minecraft:log@*"),
                group("set", "minecraft:log@[0,4]"),
                group("single", "minecraft:log@0"),
                group("same-level-first", "minecraft:stone@0"),
                group("same-level-second", "minecraft:stone@*")));

        Assert.assertEquals("single", rules.selectGroup("minecraft:log", 0).id());
        Assert.assertEquals("set", rules.selectGroup("minecraft:log", 4).id());
        Assert.assertEquals("wild", rules.selectGroup("minecraft:log", 8).id());
        Assert.assertEquals("same-level-first", rules.selectGroup("minecraft:stone", 0).id());
    }

    @Test
    public void invalidIdsEmptyGroupsAndSelectorsAreRejected() {
        Assert.assertFalse(ObjectGroupParser.parse(Arrays.asList(groupMap("", "minecraft:log@0"))).isValid());
        Assert.assertFalse(ObjectGroupParser.parse(Collections.singletonList(groupMap("a"))).isValid());
        Assert.assertFalse(ObjectGroupParser.parse(Arrays.asList(groupMap("a", "minecraft:log@16"))).isValid());
        Assert.assertFalse(ObjectGroupParser.parse(Arrays.asList(
                groupMap("a", "minecraft:log@0"), groupMap("a", "minecraft:stone@0"))).isValid());
    }

    @Test
    public void ruleSetAndMembersAreImmutable() {
        ObjectGroupRuleSet rules = new ObjectGroupRuleSet(Collections.singletonList(
                group("logs", "minecraft:log@[0,4]")));
        try {
            rules.groups().add(null);
            Assert.fail("groups must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            rules.groups().get(0).members().clear();
            Assert.fail("members must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    private static ObjectGroup group(String id, String... members) {
        List<ObjectGroupSelector> selectors = new ArrayList<ObjectGroupSelector>();
        for (String member : members) {
            selectors.add(ObjectGroupParser.parseSelector(member));
        }
        return new ObjectGroup(id, selectors);
    }

    private static Map<String, Object> groupMap(String id, String... members) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("id", id);
        group.put("members", new ArrayList<String>(Arrays.asList(members)));
        return group;
    }
}
