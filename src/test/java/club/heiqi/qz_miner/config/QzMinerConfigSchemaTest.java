package club.heiqi.qz_miner.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.schema.FieldType;
import club.heiqi.config.schema.SearchPickerSpec;
import club.heiqi.config.schema.ValueKind;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;

/** 5.1.0 Schema 字面量快照与多使用方 Defaults 对齐。 */
public class QzMinerConfigSchemaTest {

    /** 以独立字面量冻结 5.1.0 全部 path/type/default，不复用生产 Defaults oracle。 */
    @Test
    public void schemaLocks510LiteralPathTypeAndDefaultSnapshotInStableOrder() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        List<Map<String, Object>> objectGroups = new ArrayList<Map<String, Object>>();
        Map<String, Object> logs = new LinkedHashMap<String, Object>();
        logs.put("id", "vanilla_logs");
        logs.put("modes", Collections.<String>emptyList());
        logs.put("members", Arrays.asList("minecraft:log@*", "minecraft:log2@*"));
        objectGroups.add(logs);
        Map<String, Object> hay = new LinkedHashMap<String, Object>();
        hay.put("id", "vanilla_hay");
        hay.put("modes", Collections.<String>emptyList());
        hay.put("members", Arrays.asList("minecraft:hay_block@[0,4,8]"));
        objectGroups.add(hay);
        Map<String, Object> redstone = new LinkedHashMap<String, Object>();
        redstone.put("id", "vanilla_redstone");
        redstone.put("modes", Collections.<String>emptyList());
        redstone.put("members", Arrays.asList("minecraft:redstone_ore@*", "minecraft:lit_redstone_ore@*"));
        objectGroups.add(redstone);

        Object[][] expected = {
                {"general.greeting", FieldType.STRING, "Hello World"},
                {"general.chainRadius", FieldType.NUMBER, Double.valueOf(8.0D)},
                {"general.chainMaxBlocks", FieldType.NUMBER, Double.valueOf(1024.0D)},
                {"general.chainLoggingShellLayers", FieldType.NUMBER, Double.valueOf(1.0D)},
                {"general.maxBreakPerTick", FieldType.NUMBER, Double.valueOf(64.0D)},
                {"general.cableReplaceMaxPerTick", FieldType.NUMBER, Double.valueOf(1024.0D)},
                {"general.chainWatchdogTimeoutTicks", FieldType.NUMBER, Double.valueOf(50.0D)},
                {"general.parallelTickMinDurationMs", FieldType.NUMBER, Double.valueOf(15.0D)},
                {"general.parallelTickServerWorkBudgetUnits", FieldType.NUMBER, Double.valueOf(640.0D)},
                {"general.enableUnlimitedOreFortune", FieldType.BOOLEAN, Boolean.FALSE},
                {"general.enableFortuneForPlacedOre", FieldType.BOOLEAN, Boolean.FALSE},
                {"client.clientEnablePreviewRender", FieldType.BOOLEAN, Boolean.TRUE},
                {"client.tunnelDirectionSource", FieldType.CHOICE, "look_direction"},
                {"client.autoToolSwapEnabled", FieldType.BOOLEAN, Boolean.TRUE},
                {"client.autoToolTakeoverEnabled", FieldType.BOOLEAN, Boolean.TRUE},
                {"client.autoToolPrioritySelectors", FieldType.SIMPLE_LIST, Collections.<String>emptyList()},
                {"client.parallelTickClientWorkBudgetUnits", FieldType.NUMBER, Double.valueOf(640.0D)},
                {"client.clientPreviewMaxRadius", FieldType.NUMBER, Double.valueOf(16.0D)},
                {"client.clientPreviewMaxTargets", FieldType.NUMBER, Double.valueOf(1024.0D)},
                {"client.clientPreviewAlphaFadeStartRadius", FieldType.NUMBER, Double.valueOf(2.0D)},
                {"client.clientPreviewAlphaFadeEndRadius", FieldType.NUMBER, Double.valueOf(6.0D)},
                {"client.clientPreviewAlphaStartValue", FieldType.NUMBER, Double.valueOf(0.78D)},
                {"client.clientPreviewAlphaEndValue", FieldType.NUMBER, Double.valueOf(0.15D)},
                {"client.objectGroups", FieldType.STRUCTURED_LIST, objectGroups}
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

    /** 校验多个生产使用方继续对齐共享 Defaults；本方法不承担 5.1.0 字面量快照职责。 */
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
