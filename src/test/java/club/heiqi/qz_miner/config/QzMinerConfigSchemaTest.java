package club.heiqi.qz_miner.config;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.schema.FieldType;
import club.heiqi.config.schema.SearchPickerSpec;
import club.heiqi.config.schema.ValueKind;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;

/**
 * Schema 字段完备性与 Defaults 对齐。
 */
public class QzMinerConfigSchemaTest {

    @Test
    public void schemaLocksEveryPathTypeAndDefaultInStableOrder() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        Object[][] expected = {
                {"general.greeting", FieldType.STRING, QzMinerConfigDefaults.GREETING},
                {"general.chainRadius", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.CHAIN_RADIUS)},
                {"general.chainMaxBlocks", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.CHAIN_MAX_BLOCKS)},
                {"general.chainLoggingShellLayers", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.CHAIN_LOGGING_SHELL_LAYERS)},
                {"general.maxBreakPerTick", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.MAX_BREAK_PER_TICK)},
                {"general.cableReplaceMaxPerTick", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.CABLE_REPLACE_MAX_PER_TICK)},
                {"general.chainWatchdogTimeoutTicks", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.CHAIN_WATCHDOG_TIMEOUT_TICKS)},
                {"general.parallelTickMinDurationMs", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.PARALLEL_TICK_MIN_DURATION_MS)},
                {"general.parallelTickServerWorkBudgetUnits", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.PARALLEL_TICK_SERVER_WORK_BUDGET_UNITS)},
                {"general.enableUnlimitedOreFortune", FieldType.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.ENABLE_UNLIMITED_ORE_FORTUNE)},
                {"general.enableFortuneForPlacedOre", FieldType.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.ENABLE_FORTUNE_FOR_PLACED_ORE)},
                {"client.clientEnablePreviewRender", FieldType.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER)},
                {"client.tunnelDirectionSource", FieldType.CHOICE, QzMinerConfigDefaults.CLIENT_TUNNEL_DIRECTION_SOURCE},
                {"client.autoToolSwapEnabled", FieldType.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.CLIENT_AUTO_TOOL_SWAP_ENABLED)},
                {"client.autoToolTakeoverEnabled", FieldType.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.CLIENT_AUTO_TOOL_TAKEOVER_ENABLED)},
                {"client.autoToolPrioritySelectors", FieldType.SIMPLE_LIST, java.util.Collections.<String>emptyList()},
                {"client.parallelTickClientWorkBudgetUnits", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.PARALLEL_TICK_CLIENT_WORK_BUDGET_UNITS)},
                {"client.clientPreviewMaxRadius", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_RADIUS)},
                {"client.clientPreviewMaxTargets", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS)},
                {"client.clientPreviewAlphaFadeStartRadius", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS)},
                {"client.clientPreviewAlphaFadeEndRadius", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS)},
                {"client.clientPreviewAlphaStartValue", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE)},
                {"client.clientPreviewAlphaEndValue", FieldType.NUMBER, Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_END_VALUE)},
                {"client.objectGroups", FieldType.STRUCTURED_LIST, QzMinerConfigDefaults.objectGroups()}
        };
        List<FieldSpec> fields = new ArrayList<FieldSpec>(schema.allFields());

        Assert.assertEquals("qz_miner", schema.modId());
        Assert.assertEquals(expected.length, fields.size());
        for (int index = 0; index < expected.length; index++) {
            FieldSpec field = fields.get(index);
            Assert.assertEquals("path " + index, expected[index][0], field.path());
            Assert.assertEquals(field.path(), expected[index][1], field.type());
            Assert.assertEquals(field.path(), expected[index][2], field.defaultValue());
            Assert.assertSame(field.path(), field, schema.field(field.path()));
        }
    }

    @Test
    public void defaultsAlignWithQzMinerConfigDefaults() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        FieldSpec greeting = schema.field("general.greeting");
        Assert.assertEquals(FieldType.STRING, greeting.type());
        Assert.assertEquals(QzMinerConfigDefaults.GREETING, greeting.defaultValue());

        FieldSpec radius = schema.field("general.chainRadius");
        Assert.assertEquals(FieldType.NUMBER, radius.type());
        Assert.assertEquals(Double.valueOf(QzMinerConfigDefaults.CHAIN_RADIUS), radius.defaultValue());

        FieldSpec preview = schema.field("client.clientEnablePreviewRender");
        Assert.assertEquals(Boolean.valueOf(QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER),
                preview.defaultValue());
        FieldSpec direction = schema.field("client.tunnelDirectionSource");
        Assert.assertEquals(FieldType.CHOICE, direction.type());
        Assert.assertEquals(QzMinerConfigDefaults.CLIENT_TUNNEL_DIRECTION_SOURCE, direction.defaultValue());
        Assert.assertArrayEquals(TunnelDirectionSource.ids(),
                direction.constraints().choices().toArray(new String[0]));

        FieldSpec toolSwap = schema.field("client.autoToolSwapEnabled");
        Assert.assertEquals(Boolean.TRUE, toolSwap.defaultValue());
        Assert.assertEquals(Boolean.TRUE, schema.field("client.autoToolTakeoverEnabled").defaultValue());
        FieldSpec selectors = schema.field("client.autoToolPrioritySelectors");
        Assert.assertEquals(FieldType.SIMPLE_LIST, selectors.type());
        Assert.assertEquals(java.util.Collections.emptyList(), selectors.defaultValue());

        FieldSpec alphaStart = schema.field("client.clientPreviewAlphaStartValue");
        Assert.assertEquals(Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE),
                alphaStart.defaultValue());

        FieldSpec groups = schema.field("client.objectGroups");
        Assert.assertEquals(FieldType.STRUCTURED_LIST, groups.type());
        Assert.assertEquals("对象组", groups.label());
        Assert.assertEquals("按组标识和适用模式组织连锁挖掘对象", groups.helper());
        Assert.assertFalse(groups.helper().contains("已配置方块规则"));
        Assert.assertEquals("id", groups.valueSpec().element().identityMember());
        Assert.assertEquals(ValueKind.LIST, groups.valueSpec().element().member("modes").spec().kind());
        Assert.assertEquals(ValueKind.CHOICE,
                groups.valueSpec().element().member("modes").spec().element().kind());
        Assert.assertArrayEquals(ObjectGroupMode.ids(), groups.valueSpec().element()
                .member("modes").spec().element().choices().toArray(new String[0]));
        club.heiqi.config.schema.ValueSpec members = groups.valueSpec().element().member("members").spec();
        Assert.assertEquals(ValueKind.LIST, members.kind());
        Assert.assertEquals(ValueKind.STRING, members.element().kind());
        Assert.assertTrue(members.widget() instanceof SearchPickerSpec);
        Assert.assertEquals("qz_miner:block-selector", ((SearchPickerSpec) members.widget()).editorId());
        Assert.assertEquals(64, ((SearchPickerSpec) members.widget()).maxItems());
        Assert.assertEquals(SearchPickerSpec.BindingMode.LIST_MEMBERS,
                ((SearchPickerSpec) members.widget()).bindingMode());
    }
}
