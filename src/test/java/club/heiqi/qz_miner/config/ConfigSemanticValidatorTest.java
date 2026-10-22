package club.heiqi.qz_miner.config;

import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.config.ConfigSemanticValidator.ParseOutcome;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.uilib.ui.config.ConfigDocument;

/** 保留业务 parser 的精确错误路径、跨键反例及只读快照证据。 */
public class ConfigSemanticValidatorTest {
    @Test
    public void selectorErrorsPointToExactIndexAndDuplicatesUseCanonicalText() {
        ConfigDocument candidate = defaults().withAll(ConfigTestSupport.changes("client.autoToolPrioritySelectors",
                Arrays.asList(" minecraft:iron_pickaxe@* ", "minecraft:iron_pickaxe@*", "bad")));
        ParseOutcome outcome = ConfigSemanticValidator.validate(candidate);
        Assert.assertFalse(outcome.isValid());
        Assert.assertTrue(outcome.result.errors().containsKey("client.autoToolPrioritySelectors[1]"));
        Assert.assertTrue(outcome.result.errors().containsKey("client.autoToolPrioritySelectors[2]"));
    }

    @Test
    public void validatedSelectorsAreCanonicalAndImmutable() {
        ConfigDocument candidate = defaults().withAll(ConfigTestSupport.changes("client.autoToolPrioritySelectors",
                Arrays.asList(" ore:toolPickaxe ", "mod:tool@7")));
        ValidatedSnapshot snapshot = ConfigSemanticValidator.validate(candidate).snapshot;
        Assert.assertEquals("ore:toolPickaxe", snapshot.autoToolPrioritySelectors.get(0).canonicalText());
        try {
            snapshot.autoToolPrioritySelectors.clear();
            Assert.fail("派生选择器必须不可变");
        } catch (UnsupportedOperationException expected) {
            Assert.assertEquals(2, candidate.get(QzMinerConfigSchema.AUTO_TOOL_PRIORITY_SELECTORS).size());
        }
    }

    @Test
    public void fadeDistanceViolationMarksBothKeys() {
        ParseOutcome outcome = ConfigSemanticValidator.validate(defaults().withAll(ConfigTestSupport.changes(
                "client.clientPreviewAlphaFadeStartRadius", 5.0,
                "client.clientPreviewAlphaFadeEndRadius", 5.0)));
        Assert.assertFalse(outcome.isValid());
        Assert.assertTrue(outcome.result.errors().containsKey("client.clientPreviewAlphaFadeStartRadius"));
        Assert.assertTrue(outcome.result.errors().containsKey("client.clientPreviewAlphaFadeEndRadius"));
    }

    @Test
    public void increasingAlphaViolationMarksBothKeys() {
        ParseOutcome outcome = ConfigSemanticValidator.validate(defaults().withAll(ConfigTestSupport.changes(
                "client.clientPreviewAlphaStartValue", 0.1,
                "client.clientPreviewAlphaEndValue", 0.9)));
        Assert.assertFalse(outcome.isValid());
        Assert.assertTrue(outcome.result.errors().containsKey("client.clientPreviewAlphaStartValue"));
        Assert.assertTrue(outcome.result.errors().containsKey("client.clientPreviewAlphaEndValue"));
    }

    @Test
    public void tickBudgetInclusiveBoundsAreAcceptedWithoutRounding() {
        for (double value : new double[] {1.0, 40.0}) {
            ConfigDocument document = defaults().withAll(ConfigTestSupport.changes("general.tickBudgetMs", value));
            Assert.assertEquals((int) value, ConfigSemanticValidator.validate(document).snapshot.tickBudgetMs);
        }
    }

    private static ConfigDocument defaults() {
        return ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA);
    }
}
