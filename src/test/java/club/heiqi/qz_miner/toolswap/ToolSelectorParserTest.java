package club.heiqi.qz_miner.toolswap;

import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

/** 工具 selector 严格语法与纯文本保留合同。 */
public class ToolSelectorParserTest {

    @Test
    public void parsesWildcardExactOreAndTrimsCanonicalText() {
        Assert.assertEquals("minecraft:iron_pickaxe@*",
                ToolSelectorParser.parse(" minecraft:iron_pickaxe@* ").canonicalText());
        Assert.assertEquals(Integer.valueOf(17),
                ToolSelectorParser.parse("mod:drill@17").subtype());
        Assert.assertEquals("ore:toolPickaxe",
                ToolSelectorParser.parse("ore:  toolPickaxe  ").canonicalText());
    }

    @Test
    public void rejectsMalformedNegativeOverflowAndEmptyOre() {
        assertInvalid("minecraft:pickaxe");
        assertInvalid("minecraft:pickaxe@-1");
        assertInvalid("minecraft:pickaxe@2147483648");
        assertInvalid("ore:");
        assertInvalid("Minecraft:pickaxe@*");
    }

    @Test
    public void listReportsNormalizedDuplicateAtItsIndex() {
        ToolSelectorParser.ParseResult parsed = ToolSelectorParser.parseList(Arrays.asList(
                " mod:unknown@* ", "mod:unknown@*", "ore:unknownOre"), "client.selectors");

        Assert.assertFalse(parsed.isValid());
        Assert.assertTrue(parsed.errors().containsKey("client.selectors[1]"));
        Assert.assertEquals(2, parsed.selectors().size());
    }

    @Test
    public void legalUnknownNamesRemainWithoutRegistryLookup() {
        ToolSelector selector = ToolSelectorParser.parse("futuremod:quantum_tool@3");
        Assert.assertEquals("futuremod:quantum_tool@3", selector.canonicalText());
    }

    private static void assertInvalid(String value) {
        try {
            ToolSelectorParser.parse(value);
            Assert.fail("expected invalid selector: " + value);
        } catch (IllegalArgumentException expected) {
            // 合同断言
        }
    }
}
