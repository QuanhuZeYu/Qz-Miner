package club.heiqi.qz_miner.config;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.runtime.SaveOutcome;
import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ParseOutcome;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;

/**
 * B0.1 配置档位的行为契约（纯 JVM，不加载 GL / 不跑客户端）。
 *
 * <p>覆盖：Schema / Defaults / 旧 cfg 导入 / Config 静态字段 / 分侧发布 五处同源；26 个新键的
 * 默认值（接口冻结 §E 目标默认）；数值范围收窄与 CHOICE 白名单；恢复默认回到目标默认；
 * 既有 YAML 缺键时回落到默认。</p>
 */
public class PreviewConfigSurfaceTest {

    /** 每行：字段全路径、接口冻结 §E 目标默认（规范化字符串）。 */
    private static final String[][] NEW_KEYS = {
            {"general.parallelBudgetMode", "deadline"},
            {"general.parallelSliceBudgetMs", "4.0"},
            {"client.clientPreviewRenderBackend", "auto"},
            {"client.clientPreviewBarThickness", "0.045"},
            {"client.clientPreviewColorSource", "builtin"},
            // normalize() 走 Double.toString：>= 1e7 的值是科学计数法（这不是笔误）
            {"client.clientPreviewColorChain", "4253439.0"},
            {"client.clientPreviewColorArea", "1.5224892E7"},
            {"client.clientPreviewColorInteract", "5824634.0"},
            {"client.clientPreviewColorSecondary", "1.1570431E7"},
            {"client.clientPreviewColorRemote", "9415120.0"},
            {"client.clientPreviewColorTruncated", "1.6304216E7"},
            {"client.clientPreviewDepthMode", "xray"},
            {"client.clientPreviewAnimation", "off"},
            {"client.clientPreviewAnimationDurationMs", "120.0"},
            {"client.clientPreviewAnimationPhase", "order"},
            {"client.clientPreviewFadeMode", "timer"},
            {"client.clientPreviewFadeRefreshDistance", "0.5"},
            {"client.clientPreviewFadeFallbackMs", "250.0"},
            {"client.clientPreviewMinScreenWidthPx", "0.0"},
            {"client.clientPreviewOutlineWidthPx", "1.5"},
            // 观感默认上调（用户裁定 2026-09-14）：面明暗默认开启
            {"client.clientPreviewFaceShading", "true"},
            {"client.clientPreviewTruncationSignal", "false"},
            {"client.clientPreviewMaxTargetsHardCap", "4096.0"},
            {"client.clientPreviewLod", "off"},
            {"client.clientPreviewLodMinAlpha", "0.05"},
            {"client.clientPreviewOrderMinBrightness", "0.55"},
            {"client.clientPreviewSuppressVanillaHighlight", "false"},
            {"client.clientPreviewVersionedInputs", "false"},
            {"client.clientPreviewPresentationOverlay", "false"},
            {"client.clientPreviewExecutionProgress", "false"},
            {"client.clientPreviewBackendDiagnostics", "false"},
            {"client.clientPreviewRemoteTimeoutMs", "5000.0"}
    };

    /** 每行：字段全路径、下界（含）、上界（含）、低于下界的值、高于上界的值。 */
    private static final String[][] RANGE_CASES = {
            {"general.parallelSliceBudgetMs", "1", "40", "0", "41"},
            {"client.clientPreviewBarThickness", "0.005", "0.2", "0.004", "0.21"},
            {"client.clientPreviewColorChain", "0", "16777215", "-1", "16777216"},
            {"client.clientPreviewColorArea", "0", "16777215", "-1", "16777216"},
            {"client.clientPreviewColorInteract", "0", "16777215", "-1", "16777216"},
            {"client.clientPreviewColorSecondary", "0", "16777215", "-1", "16777216"},
            {"client.clientPreviewColorRemote", "0", "16777215", "-1", "16777216"},
            {"client.clientPreviewColorTruncated", "0", "16777215", "-1", "16777216"},
            {"client.clientPreviewAnimationDurationMs", "0", "2000", "-1", "2001"},
            {"client.clientPreviewFadeRefreshDistance", "0", "8", "-0.1", "8.1"},
            {"client.clientPreviewFadeFallbackMs", "50", "5000", "49", "5001"},
            {"client.clientPreviewMinScreenWidthPx", "0", "8", "-0.1", "8.1"},
            {"client.clientPreviewOutlineWidthPx", "0", "8", "-0.1", "8.1"},
            {"client.clientPreviewMaxTargetsHardCap", "1", "4096", "0", "4097"},
            {"client.clientPreviewLodMinAlpha", "0", "1", "-0.1", "1.1"},
            {"client.clientPreviewOrderMinBrightness", "0", "1", "-0.1", "1.1"},
            {"client.clientPreviewRemoteTimeoutMs", "250", "60000", "249", "60001"}
    };

    /** CHOICE 字段：必须以合法 id 落到枚举，未知 id 一律拒绝。 */
    private static final String[] CHOICE_KEYS = {
            "general.parallelBudgetMode",
            "client.clientPreviewRenderBackend",
            "client.clientPreviewColorSource",
            "client.clientPreviewDepthMode",
            "client.clientPreviewAnimation",
            "client.clientPreviewAnimationPhase",
            "client.clientPreviewFadeMode",
            "client.clientPreviewLod"
    };

    private File tempDir;

    /** 隔离临时目录与静态字段。 */
    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-preview-config-").toFile();
        ConfigBootstrap.resetForTests();
        resetStatics();
    }

    /** 清理静态持有。 */
    @After
    public void tearDown() throws Exception {
        ConfigBootstrap.resetForTests();
        resetStatics();
        deleteRecursively(tempDir);
    }

    /** Schema 默认、Defaults 默认 map、Config 静态字段、Authority 快照四处逐键一致。 */
    @Test
    public void newKeysShareOneDefaultAcrossSchemaDefaultsStaticsAndSnapshot() throws Exception {
        ConfigSchema schema = QzMinerConfigSchema.create();
        Map<String, Object> defaults = defaultMap();
        ValidatedSnapshot snapshot = defaultsSnapshot();
        for (String[] row : NEW_KEYS) {
            String path = row[0];
            String expected = row[1];
            FieldSpec spec = schema.field(path);
            Assert.assertNotNull("schema 缺少 " + path, spec);
            Assert.assertEquals(path + " schema 默认", expected, normalize(spec.defaultValue()));
            Assert.assertEquals(path + " putAllDefaults 默认", expected, normalize(defaults.get(path)));
            Assert.assertEquals(path + " Config 静态初值", expected, normalize(staticField(path)));
            Assert.assertEquals(path + " 快照默认", expected, normalize(snapshotField(path, snapshot)));
        }
    }

    /** 分侧发布：污染静态字段后 applyAll 必须把 25 个新键回灌为目标默认。 */
    @Test
    public void bridgeRepublishesNewKeysFromCommittedSnapshot() throws Exception {
        paintStaticsWithNonDefaults();
        ValidatedSnapshot snapshot = defaultsSnapshot();
        ConfigValueBridge.applyAll(snapshot);
        for (String[] row : NEW_KEYS) {
            Assert.assertEquals(row[0] + " 发布值", row[1], normalize(staticField(row[0])));
        }
    }

    /** 越界值一律拒绝（不夹取、不 round），边界内取值必须接受。 */
    @Test
    public void validatorNarrowsEveryNewNumberRange() throws Exception {
        ConfigManager manager = newManager("range.yaml");
        for (String[] row : RANGE_CASES) {
            assertRejected(manager, row[0], Double.valueOf(row[3]), "低于下界");
            assertRejected(manager, row[0], Double.valueOf(row[4]), "高于上界");
            assertRejected(manager, row[0], Double.valueOf(Double.NaN), "NaN");
            assertAccepted(manager, row[0], Double.valueOf(row[1]), "下界（含）");
            assertAccepted(manager, row[0], Double.valueOf(row[2]), "上界（含）");
        }
    }

    /** CHOICE 字段拒绝未知 id；合法 id 落到对应枚举（未知不回落默认）。 */
    @Test
    public void validatorRejectsUnknownChoices() throws Exception {
        ConfigManager manager = newManager("choice.yaml");
        for (String path : CHOICE_KEYS) {
            assertRejected(manager, path, "bogus", "未知 id");
            assertRejected(manager, path, Double.valueOf(1.0), "非字符串");
        }
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("general.parallelBudgetMode", "slice");
        draft.setDraft("client.clientPreviewRenderBackend", "legacy");
        draft.setDraft("client.clientPreviewColorSource", "config");
        draft.setDraft("client.clientPreviewDepthMode", "occlude");
        draft.setDraft("client.clientPreviewAnimation", "wave");
        draft.setDraft("client.clientPreviewAnimationPhase", "hash");
        draft.setDraft("client.clientPreviewFadeMode", "signal");
        draft.setDraft("client.clientPreviewLod", "auto");
        Assert.assertTrue(manager.save(draft).isSuccess());
        ValidatedSnapshot snapshot = ConfigSemanticValidator.captureAndValidate(manager).snapshot;
        Assert.assertEquals("deadline 之外必须落到 slice", "slice", snapshot.parallelBudgetMode);
        Assert.assertEquals(PreviewRenderBackend.LEGACY, snapshot.clientPreviewRenderBackend);
        Assert.assertEquals(PreviewColorSource.CONFIG, snapshot.clientPreviewColorSource);
        Assert.assertEquals(PreviewDepthMode.OCCLUDE, snapshot.clientPreviewDepthMode);
        Assert.assertEquals(PreviewAnimationMode.WAVE, snapshot.clientPreviewAnimation);
        Assert.assertEquals(PreviewAnimationPhase.HASH, snapshot.clientPreviewAnimationPhase);
        Assert.assertEquals(PreviewFadeMode.SIGNAL, snapshot.clientPreviewFadeMode);
        Assert.assertEquals(PreviewLodMode.AUTO, snapshot.clientPreviewLod);
    }

    /** 恢复默认：把 25 个键全改成非默认值后逐字段 resetFieldToDefault，必须回到目标默认。 */
    @Test
    public void restoreDefaultsReturnsEveryNewKeyToTargetDefault() throws Exception {
        ConfigManager manager = newManager("restore.yaml");
        DraftBuffer mutated = manager.openDraft();
        for (String[] row : NEW_KEYS) {
            mutated.setDraft(row[0], nonDefaultFor(row[0]));
        }
        Assert.assertTrue(manager.save(mutated).isSuccess());
        ValidatedSnapshot mutatedSnapshot = ConfigSemanticValidator.captureAndValidate(manager).snapshot;
        Assert.assertEquals("slice", mutatedSnapshot.parallelBudgetMode);
        Assert.assertEquals(1.0D, mutatedSnapshot.clientPreviewMaxTargetsHardCap, 1e-9);

        DraftBuffer restored = manager.openDraft();
        for (String[] row : NEW_KEYS) {
            restored.resetFieldToDefault(row[0]);
        }
        Assert.assertTrue(manager.save(restored).isSuccess());
        ValidatedSnapshot snapshot = ConfigSemanticValidator.captureAndValidate(manager).snapshot;
        for (String[] row : NEW_KEYS) {
            Assert.assertEquals(row[0] + " 恢复默认", row[1], normalize(snapshotField(row[0], snapshot)));
        }
    }

    /** 既有 YAML 缺键（旧档位）导入：缺失字段回落到 Schema 默认，不报错。 */
    @Test
    public void existingYamlWithoutNewKeysFallsBackToDefaults() throws Exception {
        File partial = new File(tempDir, "legacy-partial.yaml");
        Files.write(partial.toPath(), ("general:\n"
                + "  greeting: Hi\n"
                + "client:\n"
                + "  clientEnablePreviewRender: false\n"
                + "  clientPreviewMaxRadius: 8\n").getBytes("UTF-8"));
        ConfigManager manager = ConfigManager.bootstrap(
                partial, QzMinerConfigSchema.create(), ConfigSemanticValidator.draftValidator());
        ParseOutcome outcome = ConfigSemanticValidator.captureAndValidate(manager);
        Assert.assertTrue(outcome.result.summary(), outcome.isValid());
        Assert.assertEquals("缺键不得改变既有键值", "Hi", outcome.snapshot.greeting);
        Assert.assertFalse(outcome.snapshot.clientEnablePreviewRender);
        Assert.assertEquals(8, outcome.snapshot.clientPreviewMaxRadius);
        for (String[] row : NEW_KEYS) {
            Assert.assertEquals(row[0] + " 缺键回落默认", row[1], normalize(snapshotField(row[0], outcome.snapshot)));
        }
    }

    private void assertAccepted(ConfigManager manager, String path, Object value, String label) {
        DraftBuffer draft = manager.openDraft();
        draft.setDraft(path, value);
        SaveOutcome outcome = manager.save(draft);
        Assert.assertEquals(path + " " + label + " 必须接受", SaveOutcome.Status.OK, outcome.status());
    }

    private void assertRejected(ConfigManager manager, String path, Object value, String label) {
        DraftBuffer draft = manager.openDraft();
        draft.setDraft(path, value);
        SaveOutcome outcome = manager.save(draft);
        Assert.assertEquals(path + " " + label + " 必须被拒绝", SaveOutcome.Status.INVALID, outcome.status());
        Assert.assertNotNull(path + " " + label + " 必须带字段级错误",
                outcome.validation() == null ? null : outcome.validation().errorFor(path));
    }

    private ConfigManager newManager(String fileName) throws Exception {
        return ConfigManager.bootstrap(new File(tempDir, fileName),
                QzMinerConfigSchema.create(), ConfigSemanticValidator.draftValidator());
    }

    private ValidatedSnapshot defaultsSnapshot() throws Exception {
        ConfigManager manager = newManager("defaults.yaml");
        ParseOutcome outcome = ConfigSemanticValidator.captureAndValidate(manager);
        Assert.assertTrue(outcome.result.summary(), outcome.isValid());
        return outcome.snapshot;
    }

    private static Map<String, Object> defaultMap() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        QzMinerConfigDefaults.putAllDefaults(values);
        return values;
    }

    private static Object staticField(String path) throws Exception {
        Field field = Config.class.getField(leaf(path));
        return field.get(null);
    }

    private static Object snapshotField(String path, ValidatedSnapshot snapshot) throws Exception {
        Field field = ValidatedSnapshot.class.getField(leaf(path));
        return field.get(snapshot);
    }

    private static String leaf(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    /** 规范化成可比较字符串：Number → double 文本，枚举 → 配置 id，其余原样。 */
    private static String normalize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number) {
            return String.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof Boolean || value instanceof String) {
            return String.valueOf(value);
        }
        try {
            return String.valueOf(value.getClass().getMethod("id").invoke(value));
        } catch (Exception e) {
            throw new AssertionError("无法规范化 " + value.getClass().getName(), e);
        }
    }

    /** 为恢复默认用例生成「合法但非默认」的取值。 */
    private static Object nonDefaultFor(String path) {
        String leaf = leaf(path);
        if (leaf.equals("parallelBudgetMode")) {
            return "slice";
        }
        if (leaf.equals("parallelSliceBudgetMs")) {
            return Double.valueOf(40.0);
        }
        if (leaf.equals("clientPreviewRenderBackend")) {
            return "legacy";
        }
        if (leaf.equals("clientPreviewColorSource")) {
            return "config";
        }
        if (leaf.equals("clientPreviewDepthMode")) {
            return "occlude";
        }
        if (leaf.equals("clientPreviewAnimation")) {
            return "wave";
        }
        if (leaf.equals("clientPreviewAnimationPhase")) {
            return "hash";
        }
        if (leaf.equals("clientPreviewFadeMode")) {
            return "signal";
        }
        if (leaf.equals("clientPreviewLod")) {
            return "auto";
        }
        if (leaf.equals("clientPreviewSuppressVanillaHighlight") || leaf.equals("clientPreviewVersionedInputs")
                || leaf.equals("clientPreviewPresentationOverlay")
                || leaf.equals("clientPreviewExecutionProgress")
                || leaf.equals("clientPreviewBackendDiagnostics")
                || leaf.equals("clientPreviewFaceShading")) {
            return Boolean.TRUE.equals(staticDefault(leaf)) ? Boolean.FALSE : Boolean.TRUE;
        }
        if (leaf.equals("clientPreviewTruncationSignal")) {
            return Boolean.TRUE;
        }
        if (leaf.equals("clientPreviewColorChain") || leaf.equals("clientPreviewColorArea")
                || leaf.equals("clientPreviewColorInteract") || leaf.equals("clientPreviewColorSecondary")
                || leaf.equals("clientPreviewColorRemote") || leaf.equals("clientPreviewColorTruncated")) {
            return Double.valueOf(0.0);
        }
        if (leaf.equals("clientPreviewMaxTargetsHardCap")) {
            return Double.valueOf(1.0);
        }
        return Double.valueOf(maxOf(leaf));
    }

    private static Boolean staticDefault(String leaf) {
        try {
            return (Boolean) Config.class.getField(leaf).get(null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static double maxOf(String leaf) {
        if (leaf.equals("clientPreviewBarThickness")) {
            return 0.2D;
        }
        if (leaf.equals("clientPreviewAnimationDurationMs")) {
            return 2000.0D;
        }
        if (leaf.equals("clientPreviewFadeRefreshDistance") || leaf.equals("clientPreviewMinScreenWidthPx")
                || leaf.equals("clientPreviewOutlineWidthPx")) {
            return 8.0D;
        }
        if (leaf.equals("clientPreviewFadeFallbackMs")) {
            return 5000.0D;
        }
        if (leaf.equals("clientPreviewLodMinAlpha") || leaf.equals("clientPreviewOrderMinBrightness")) {
            return 1.0D;
        }
        if (leaf.equals("clientPreviewRemoteTimeoutMs")) {
            return 60000.0D;
        }
        throw new AssertionError("缺少非默认取值: " + leaf);
    }

    /** 把新键静态字段污染成非默认值（仅 legal 取值）。 */
    private void paintStaticsWithNonDefaults() throws Exception {
        for (String[] row : NEW_KEYS) {
            Object value = nonDefaultFor(row[0]);
            Field field = Config.class.getField(leaf(row[0]));
            if (field.getType() == int.class && value instanceof Double) {
                field.setInt(null, (int) ((Double) value).doubleValue());
            } else if (field.getType() == double.class && value instanceof Double) {
                field.setDouble(null, ((Double) value).doubleValue());
            } else if (field.getType() == boolean.class) {
                field.setBoolean(null, ((Boolean) value).booleanValue());
            } else if (field.getType() == String.class) {
                field.set(null, value);
            } else {
                // 枚举字段：用枚举自身的 fromId 落到非默认档（legacy/occlude/off/hash/timer/auto）
                field.set(null, field.getType().getMethod("fromId", String.class).invoke(null, value));
            }
        }
    }

    private static void resetStatics() throws Exception {
        for (String[] row : NEW_KEYS) {
            String leaf = leaf(row[0]);
            Object value = defaultsFor(leaf);
            Field field = Config.class.getField(leaf);
            if (field.getType() == int.class && value instanceof Double) {
                field.setInt(null, (int) ((Double) value).doubleValue());
            } else if (field.getType() == double.class && value instanceof Double) {
                field.setDouble(null, ((Double) value).doubleValue());
            } else if (field.getType() == boolean.class) {
                field.setBoolean(null, ((Boolean) value).booleanValue());
            } else if (field.getType() == String.class) {
                field.set(null, value);
            } else {
                field.set(null, field.getType().getMethod("defaultValue").invoke(null));
            }
        }
        Config.configPath = "";
    }

    /** 目标默认可直接从 Defaults map 取（NUMBER 为 Double）。 */
    private static Object defaultsFor(String leaf) {
        Map<String, Object> values = defaultMap();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey().endsWith("." + leaf)) {
                return entry.getValue();
            }
        }
        throw new AssertionError("缺少默认值: " + leaf);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
