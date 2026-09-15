package club.heiqi.qz_miner.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.schema.ColorSpec;
import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.schema.FieldType;
import club.heiqi.config.schema.SearchPickerSpec;
import club.heiqi.config.schema.ValueKind;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;

/** Schema 与唯一 Defaults 真源的同源登记契约，以及多使用方对齐。 */
public class QzMinerConfigSchemaTest {

    /**
     * Schema 与 {@link QzMinerConfigDefaults#putAllDefaults} 的同源登记契约（替代 5.3 字面快照）。
     *
     * <p><b>为什么不冻结 path/type/default 字面量表</b>：整表快照要求每次新增配置键都改测试，而且它
     * 复制的是 schema 与 defaults 两份生产真源——两者漂移时，第三份字面量表只会被一起改成绿色。
     * 现在只守行为：① schema 字段 path 集合 == Defaults 登记键集合（缺一即红）；
     * ② 每字段默认值类型与 {@link FieldType} 匹配、NUMBER 默认值落在自身约束区间内、label/helper 非空。
     * issue #242 的键值契约由 {@link #harvestExhaustionKeyKeepsIssue242DefaultAndRange()} 单独冻结。</p>
     */
    @Test
    public void schemaAndDefaultsShareTheSameRegisteredPathSet() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        QzMinerConfigDefaults.putAllDefaults(defaults);

        Assert.assertEquals("qz_miner", schema.modId());

        Set<String> schemaPaths = new LinkedHashSet<String>();
        for (FieldSpec field : schema.allFields()) {
            String path = field.path();
            Assert.assertTrue("path 不得重复: " + path, schemaPaths.add(path));
            Assert.assertTrue("Defaults 缺少同源登记: " + path, defaults.containsKey(path));

            Object defaultValue = field.defaultValue();
            Assert.assertNotNull(path + " 缺少默认值", defaultValue);
            Assert.assertNotNull(path + " 缺少约束", field.constraints());
            switch (field.type()) {
                case STRING:
                case CHOICE:
                    Assert.assertTrue(path + " 默认值应为 String，实际 " + defaultValue.getClass().getName(),
                            defaultValue instanceof String);
                    break;
                case NUMBER:
                    Assert.assertTrue(path + " 默认值应为 Number，实际 " + defaultValue.getClass().getName(),
                            defaultValue instanceof Number);
                    double number = ((Number) defaultValue).doubleValue();
                    Assert.assertTrue(path + " 默认值必须有限，实际 " + number, Double.isFinite(number));
                    Assert.assertTrue(path + " 默认值必须落在约束区间 [" + field.constraints().min() + ","
                                    + field.constraints().max() + "]，实际 " + number,
                            number >= field.constraints().min() && number <= field.constraints().max());
                    break;
                case BOOLEAN:
                    Assert.assertTrue(path + " 默认值应为 Boolean，实际 " + defaultValue.getClass().getName(),
                            defaultValue instanceof Boolean);
                    break;
                case SIMPLE_LIST:
                case STRUCTURED_LIST:
                    Assert.assertTrue(path + " 默认值应为 List，实际 " + defaultValue.getClass().getName(),
                            defaultValue instanceof List);
                    break;
                default:
                    Assert.fail(path + " 出现未覆盖的 FieldType: " + field.type());
            }

            Assert.assertNotNull(path + " 缺少 label", field.label());
            Assert.assertFalse(path + " label 不得为空", field.label().trim().isEmpty());
            Assert.assertNotNull(path + " 缺少 helper", field.helper());
            Assert.assertFalse(path + " helper 不得为空", field.helper().trim().isEmpty());
        }
        for (String path : defaults.keySet()) {
            Assert.assertTrue("Schema 缺少 Defaults 登记的键: " + path, schemaPaths.contains(path));
        }
    }

    /**
     * Issue #242 契约：每方块饥饿值消耗默认 {@code 0.025}（= 需求原文 = 原版 {@code Block.harvestBlock}
     * 固定值），合法区间 {@code [-40, 40]}（用户裁定 2026-09-15：{@code 0} = 连锁完全不消耗、
     * 负值 = 净回补；{@code 40} = 原版 exhaustion 累加上限，{@code -40} 与之对称）。
     */
    @Test
    public void harvestExhaustionKeyKeepsIssue242DefaultAndRange() {
        FieldSpec exhaustion = QzMinerConfigSchema.create().field("general.harvestExhaustionPerBlock");
        Assert.assertNotNull("schema 缺少 issue #242 的饥饿值消耗键", exhaustion);
        Assert.assertEquals(FieldType.NUMBER, exhaustion.type());
        Assert.assertEquals(Double.valueOf(0.025D), exhaustion.defaultValue());
        Assert.assertEquals(QzMinerConfigDefaults.HARVEST_EXHAUSTION_PER_BLOCK_MIN,
                exhaustion.constraints().min(), 0.0D);
        Assert.assertTrue("0 与负值必须落在合法域内（0 = 不消耗、负值 = 净回补）",
                exhaustion.constraints().min() < 0.0D);
        Assert.assertEquals(40.0D, exhaustion.constraints().max(), 0.0D);
        Assert.assertEquals(Double.valueOf(QzMinerConfigDefaults.HARVEST_EXHAUSTION_PER_BLOCK),
                exhaustion.defaultValue());
        Assert.assertEquals(QzMinerConfigDefaults.HARVEST_EXHAUSTION_PER_BLOCK_MAX,
                exhaustion.constraints().max(), 0.0D);
    }

    /** 校验多个生产使用方继续对齐共享 Defaults；本方法不承担 5.3 字面量快照职责。 */
    @Test
    public void defaultsAlignWithQzMinerConfigDefaults() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        FieldSpec greeting = schema.field("general.greeting");
        Assert.assertEquals(FieldType.STRING, greeting.type());
        Assert.assertEquals(QzMinerConfigDefaults.GREETING, greeting.defaultValue());

        FieldSpec radius = schema.field("general.chainRadius");
        Assert.assertEquals(FieldType.NUMBER, radius.type());
        Assert.assertEquals(Double.valueOf(QzMinerConfigDefaults.CHAIN_RADIUS), radius.defaultValue());

        FieldSpec tickBudget = schema.field("general.tickBudgetMs");
        Assert.assertEquals(FieldType.NUMBER, tickBudget.type());
        Assert.assertEquals(Double.valueOf(QzMinerConfigDefaults.TICK_BUDGET_MS), tickBudget.defaultValue());
        Assert.assertNull(schema.field("general.maxBreakPerTick"));
        Assert.assertNull(schema.field("general.parallelTickMinDurationMs"));
        Assert.assertNull(schema.field("general.parallelTickServerWorkBudgetUnits"));
        Assert.assertNull(schema.field("client.parallelTickClientWorkBudgetUnits"));

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
        Assert.assertNull(schema.field("client.autoToolTakeoverEnabled"));
        FieldSpec selectors = schema.field("client.autoToolPrioritySelectors");
        Assert.assertEquals(FieldType.SIMPLE_LIST, selectors.type());
        Assert.assertEquals(java.util.Collections.emptyList(), selectors.defaultValue());

        FieldSpec alphaStart = schema.field("client.clientPreviewAlphaStartValue");
        Assert.assertEquals(Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE),
                alphaStart.defaultValue());

        // 颜色键的控件形态：由 schema 的 .color(...) 声明（按 widget 分发到 HEX 输入框），值语义仍是 NUMBER。
        // 唯一能在离线暴露「改回 .number(...) 导致颜色框静默退回十进制形态」的地方。
        for (String path : new String[] {"client.clientPreviewColorChain", "client.clientPreviewColorArea",
                "client.clientPreviewColorInteract", "client.clientPreviewColorSecondary",
                "client.clientPreviewColorRemote", "client.clientPreviewColorTruncated"}) {
            FieldSpec color = schema.field(path);
            Assert.assertEquals(path + " 值语义必须是 NUMBER", FieldType.NUMBER, color.type());
            Assert.assertTrue(path + " 必须由 .color(...) 声明 ColorSpec widget",
                    color.widget() instanceof ColorSpec);
        }

        FieldSpec groups = schema.field("client.objectGroups");
        Assert.assertEquals(FieldType.STRUCTURED_LIST, groups.type());
        Assert.assertEquals("schema 默认必须引用 QzMinerConfigDefaults.objectGroups()（唯一真源，禁止第二份字面量）",
                QzMinerConfigDefaults.objectGroups(), groups.defaultValue());
        // 只断言行为：默认组必须可用（非空、每组 modes/members 非空、每条 selector 可解析），
        // 不冻结「几组 / 几条成员」——默认对象组是随 issue 演进的覆盖列表。
        List<?> defaultGroups = (List<?>) groups.defaultValue();
        Assert.assertFalse("出厂默认对象组不得为空", defaultGroups.isEmpty());
        for (Object rawGroup : defaultGroups) {
            Map<?, ?> group = (Map<?, ?>) rawGroup;
            Assert.assertFalse("每组 modes 不得为空（空 modes 的组永不生效）: " + group.get("id"),
                    ((List<?>) group.get("modes")).isEmpty());
            Assert.assertFalse("每组 members 不得为空: " + group.get("id"),
                    ((List<?>) group.get("members")).isEmpty());
            for (Object member : (List<?>) group.get("members")) {
                ObjectGroupParser.parseSelector(String.valueOf(member));
            }
        }
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
        Assert.assertEquals(SearchPickerSpec.BindingMode.LIST_MEMBERS,
                ((SearchPickerSpec) members.widget()).bindingMode());
    }
}
