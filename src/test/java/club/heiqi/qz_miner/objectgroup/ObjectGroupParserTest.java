package club.heiqi.qz_miner.objectgroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/** 对象组 mode、selector 规范化、交集和不可变快照测试。 */
public class ObjectGroupParserTest {

    @Test
    public void parsesAllSelectorFormsAndCanonicalizesSetOrder() {
        Assert.assertEquals("minecraft:log@0", ObjectGroupParser.parseSelector("minecraft:log@0").canonical());
        Assert.assertEquals("minecraft:log@*", ObjectGroupParser.parseSelector("minecraft:log@*").canonical());
        Assert.assertEquals("minecraft:log@[0,4,8,12]",
                ObjectGroupParser.parseSelector("minecraft:log@[12,0,8,4]").canonical());
    }

    @Test
    public void mixedCaseRegistryIsPreservedAndMatchedCaseSensitively() {
        ObjectGroupSelector selector = ObjectGroupParser.parseSelector("GalaxySpace:barnardaCleaves@*");

        Assert.assertEquals("GalaxySpace:barnardaCleaves", selector.registry());
        Assert.assertEquals("GalaxySpace:barnardaCleaves@*", selector.canonical());
        Assert.assertTrue(selector.matches("GalaxySpace:barnardaCleaves", 0));
        Assert.assertFalse(selector.matches("galaxyspace:barnardacleaves", 0));
    }

    @Test
    public void registrySyntaxStillRejectsExtraColonUnicodeAndUnlistedPunctuation() {
        assertInvalidSelector("GalaxySpace:barnarda:Cleaves@*");
        assertInvalidSelector("GaláxySpace:barnardaCleaves@*");
        assertInvalidSelector("GalaxySpace:barnardaCleaves!@*");
    }

    @Test
    public void sameGroupSelectorsNormalizeByRegistryMask() {
        ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(Arrays.asList(
                groupMap("logs", modes(ObjectGroupMode.CHAIN_BASE),
                        "minecraft:log@0", "minecraft:log@[4,8]", "minecraft:stone@0")));

        Assert.assertTrue(parsed.isValid());
        Assert.assertEquals(2, parsed.rules().groups().get(0).members().size());
        Assert.assertEquals("minecraft:log@[0,4,8]",
                parsed.rules().groups().get(0).members().get(0).canonical());
        Assert.assertEquals(1L, parsed.rules().groups().get(0).modeMask());
    }

    @Test
    public void overlapRequiresSharedModeAndIntersectingSelector() {
        Assert.assertFalse(ObjectGroupParser.parse(Arrays.asList(
                groupMap("a", modes(ObjectGroupMode.CHAIN_BASE), "minecraft:log@*"),
                groupMap("b", modes(ObjectGroupMode.CHAIN_BASE), "minecraft:log@0"))).isValid());
        Assert.assertTrue(ObjectGroupParser.parse(Arrays.asList(
                groupMap("a", modes(ObjectGroupMode.CHAIN_BASE), "minecraft:log@*"),
                groupMap("b", modes(ObjectGroupMode.CHAIN_ORE), "minecraft:log@0"))).isValid());
        Assert.assertTrue(ObjectGroupParser.parse(Arrays.asList(
                groupMap("a", modes(ObjectGroupMode.CHAIN_BASE), "minecraft:log@0"),
                groupMap("b", modes(ObjectGroupMode.CHAIN_BASE), "minecraft:log@4"))).isValid());
        Assert.assertTrue(ObjectGroupParser.parse(Arrays.asList(
                groupMap("a", modes(), "minecraft:log@*"),
                groupMap("b", modes(), "minecraft:log@0"))).isValid());
    }

    @Test
    public void overlapReportsBothDraftModePaths() {
        ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(Arrays.asList(
                groupMap("a", modes(ObjectGroupMode.AREA_ORE), "minecraft:stone@[0,4]"),
                groupMap("b", modes(ObjectGroupMode.AREA_ORE), "minecraft:stone@[4,8]")));
        Assert.assertTrue(parsed.errors().containsKey("client.objectGroups[0].modes"));
        Assert.assertTrue(parsed.errors().containsKey("client.objectGroups[1].modes"));
    }

    @Test
    public void missingModesMigratesToEmptyMask() {
        ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(
                Collections.singletonList(groupMap("legacy", "minecraft:log@*")));
        Assert.assertTrue(parsed.isValid());
        Assert.assertTrue(parsed.rules().groups().get(0).modes().isEmpty());
        Assert.assertEquals(0L, parsed.rules().groups().get(0).modeMask());
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
        try {
            rules.groups().get(0).modes().add(ObjectGroupMode.CHAIN_BASE);
            Assert.fail("modes must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    private static ObjectGroup group(String id, String... members) {
        List<ObjectGroupSelector> selectors = new ArrayList<ObjectGroupSelector>();
        for (String member : members) {
            selectors.add(ObjectGroupParser.parseSelector(member));
        }
        return new ObjectGroup(id, Collections.singletonList(ObjectGroupMode.CHAIN_BASE), 1L, selectors);
    }

    private static void assertInvalidSelector(String selector) {
        try {
            ObjectGroupParser.parseSelector(selector);
            Assert.fail("selector must be rejected: " + selector);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static Map<String, Object> groupMap(String id, String... members) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("id", id);
        group.put("members", new ArrayList<String>(Arrays.asList(members)));
        return group;
    }

    private static Map<String, Object> groupMap(String id, List<String> modes, String... members) {
        Map<String, Object> group = groupMap(id, members);
        group.put("modes", modes);
        return group;
    }

    private static List<String> modes(String... modes) {
        return new ArrayList<String>(Arrays.asList(modes));
    }
}
