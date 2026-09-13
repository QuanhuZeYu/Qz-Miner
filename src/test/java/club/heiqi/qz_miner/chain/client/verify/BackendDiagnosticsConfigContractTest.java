package club.heiqi.qz_miner.chain.client.verify;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.client.ChainPreviewBackendDiagnostics;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationHeader;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationProjection;
import club.heiqi.qz_miner.config.ConfigSemanticValidator;
import club.heiqi.qz_miner.config.ConfigValueBridge;
import club.heiqi.qz_miner.config.QzMinerConfigDefaults;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;

/**
 * Q4 预览后端诊断配置面契约：五处同源 + 默认 off + 中英对称 + 值对象语义 + 投影透传。
 *
 * <p>形状照 {@link ExecutionProgressConfigContractTest}（同一「配置 → 投影位 → HUD 行」范式）：
 * 该开关是 Q4（HUD 展示「当前生效后端 / 一次性回退原因」）的唯一门控件，因此必须与
 * {@code clientPreviewExecutionProgress} 一样在 Schema / Defaults / Config / Bridge / Validator
 * 五处同源，并由语言文件提供中英文案。</p>
 */
public class BackendDiagnosticsConfigContractTest {

    private static final String SHORT_KEY = "clientPreviewBackendDiagnostics";
    private static final String CONFIG_PATH = "client.clientPreviewBackendDiagnostics";

    @Test
    public void configStaticFieldExistsAndDefaultsOff() throws Exception {
        Field field = Config.class.getField(SHORT_KEY);
        Assert.assertEquals("Config 字段必须是 boolean", boolean.class, field.getType());
        Assert.assertFalse("后端诊断开关默认必须是 off", field.getBoolean(null));
    }

    @Test
    public void defaultsTableCarriesTheKeyAsOff() {
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        QzMinerConfigDefaults.putAllDefaults(defaults);
        Assert.assertTrue("默认值表必须包含 " + CONFIG_PATH, defaults.containsKey(CONFIG_PATH));
        Assert.assertEquals("默认值必须是 false", Boolean.FALSE, defaults.get(CONFIG_PATH));
    }

    @Test
    public void validatorSnapshotCarriesTheKey() throws Exception {
        Class<?> snapshotType =
            Class.forName("club.heiqi.qz_miner.config.ConfigSemanticValidator$ValidatedSnapshot");
        Field field = snapshotType.getField(SHORT_KEY);
        Assert.assertEquals("校验快照字段必须是 boolean", boolean.class, field.getType());
    }

    @Test
    public void languageEntriesArePresentInBothLanguages() throws Exception {
        String en = readResource("assets/qz_miner/lang/en_US.lang");
        String zh = readResource("assets/qz_miner/lang/zh_CN.lang");
        assertSymmetricTooltip(en, zh, "config.qz_miner." + SHORT_KEY);
    }

    /** HUD 行文案（标签 + 回退片段）中英必须都在，且不得直接复制同一文案。 */
    @Test
    public void hudLanguageEntriesArePresentInBothLanguages() throws Exception {
        String en = readResource("assets/qz_miner/lang/en_US.lang");
        String zh = readResource("assets/qz_miner/lang/zh_CN.lang");
        assertSymmetricTooltip(en, zh, "hud.qz_miner.preview.backend.label");
        assertSymmetricTooltip(en, zh, "hud.qz_miner.preview.backend.fallback");
    }

    @Test
    public void draftSaveRoundTripBridgesTheKeyIntoConfig() throws Exception {
        boolean saved = Config.clientPreviewBackendDiagnostics;
        File yaml = File.createTempFile("qz-backend-diagnostics", ".yaml");
        try {
            ConfigManager manager = ConfigManager.bootstrap(
                yaml, QzMinerConfigSchema.create(), ConfigSemanticValidator.draftValidator());
            Assert.assertFalse("初始必须为 off", Config.clientPreviewBackendDiagnostics);
            DraftBuffer draft = manager.openDraft();
            draft.setDraft(CONFIG_PATH, Boolean.TRUE);
            club.heiqi.config.runtime.SaveOutcome outcome = manager.save(draft);
            Assert.assertEquals("合法键必须保存成功（Schema 已声明）: " + outcome.status(),
                club.heiqi.config.runtime.SaveOutcome.Status.OK, outcome.status());
            ConfigSemanticValidator.ValidatedSnapshot enabled =
                ConfigSemanticValidator.captureAndValidate(manager).snapshot;
            Assert.assertTrue("YAML true 必须解析进快照", enabled.clientPreviewBackendDiagnostics);
            ConfigValueBridge.applyClientFromSnapshot(enabled);
            Assert.assertTrue("桥接必须把 true 写进 Config", Config.clientPreviewBackendDiagnostics);

            DraftBuffer offDraft = manager.openDraft();
            offDraft.setDraft(CONFIG_PATH, Boolean.FALSE);
            manager.save(offDraft);
            ConfigSemanticValidator.ValidatedSnapshot disabled =
                ConfigSemanticValidator.captureAndValidate(manager).snapshot;
            Assert.assertFalse("YAML false 必须解析进快照", disabled.clientPreviewBackendDiagnostics);
            ConfigValueBridge.applyClientFromSnapshot(disabled);
            Assert.assertFalse("恢复默认必须写回 off", Config.clientPreviewBackendDiagnostics);
        } finally {
            Config.clientPreviewBackendDiagnostics = saved;
            yaml.delete();
        }
    }

    // ------------------------------------------------------------------ 值对象

    /** 关闭态必须收敛到共享常量：生产侧据此避免每 tick 重建实例（稳态零分配）。 */
    @Test
    public void disabledDiagnosticsCollapseToSharedConstant() {
        Assert.assertSame(ChainPreviewBackendDiagnostics.DISABLED,
            ChainPreviewBackendDiagnostics.of(false, "shader", "ensureReady-failed"));
        Assert.assertSame(ChainPreviewBackendDiagnostics.DISABLED,
            ChainPreviewBackendDiagnostics.of(false, null, null));
        Assert.assertFalse(ChainPreviewBackendDiagnostics.DISABLED.isEnabled());
    }

    @Test
    public void diagnosticsValueObjectHasValueSemantics() {
        ChainPreviewBackendDiagnostics first =
            ChainPreviewBackendDiagnostics.of(true, "shader", "");
        ChainPreviewBackendDiagnostics same =
            ChainPreviewBackendDiagnostics.of(true, "shader", "");
        ChainPreviewBackendDiagnostics otherId =
            ChainPreviewBackendDiagnostics.of(true, "legacy", "");
        ChainPreviewBackendDiagnostics otherReason =
            ChainPreviewBackendDiagnostics.of(true, "shader", "ensureReady-failed");

        Assert.assertEquals("同值必须相等（生产侧复用实例的判据）", first, same);
        Assert.assertEquals("同值 hashCode 必须一致", first.hashCode(), same.hashCode());
        Assert.assertNotEquals("后端切换必须不等", first, otherId);
        Assert.assertNotEquals("原因变化必须不等", first, otherReason);
        Assert.assertNotSame("不同值必须是不同实例", first, same);
    }

    @Test
    public void nullDiagnosticsFieldsNormalizeToEmpty() {
        ChainPreviewBackendDiagnostics diagnostics =
            ChainPreviewBackendDiagnostics.of(true, null, null);
        Assert.assertTrue(diagnostics.isEnabled());
        Assert.assertEquals("", diagnostics.getActiveBackendId());
        Assert.assertEquals("", diagnostics.getFallbackReason());
    }

    // ------------------------------------------------------------------ 投影透传

    @Test
    public void headerCarriesDiagnosticsFromProjection() {
        ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();
        ChainPreviewState state = new ChainPreviewState();

        ChainPreviewPresentationHeader disabled = projection.sampleAndPublish(
            state, null, null, 1L, 1L, 1L, 1L, 1L, false, false, 0,
            ChainPreviewBackendDiagnostics.DISABLED);
        Assert.assertFalse("关闭态 header 不得让 HUD 产出诊断行", disabled.isBackendDiagnosticsEnabled());
        Assert.assertEquals("", disabled.getActiveBackendId());

        ChainPreviewPresentationHeader enabled = projection.sampleAndPublish(
            state, null, null, 1L, 1L, 1L, 1L, 1L, false, false, 0,
            ChainPreviewBackendDiagnostics.of(true, "shader", "ensureReady-failed"));
        Assert.assertTrue("开关位必须透传", enabled.isBackendDiagnosticsEnabled());
        Assert.assertEquals("shader", enabled.getActiveBackendId());
        Assert.assertEquals("ensureReady-failed", enabled.getBackendFallbackReason());
        Assert.assertNotSame("诊断变化必须发布新 header", disabled, enabled);

        // 值不变必须零重发布（header 复用同一实例）
        Assert.assertSame("同内容必须零重发布", enabled, projection.sampleAndPublish(
            state, null, null, 1L, 1L, 1L, 1L, 1L, false, false, 0,
            ChainPreviewBackendDiagnostics.of(true, "shader", "ensureReady-failed")));
    }

    // ------------------------------------------------------------------ 辅助

    private static void assertSymmetricTooltip(String en, String zh, String prefix) {
        Assert.assertTrue("en_US 必须包含 " + prefix + " 条目", en.contains(prefix));
        Assert.assertTrue("zh_CN 必须包含 " + prefix + " 条目", zh.contains(prefix));
        String enTip = tooltip(en, prefix);
        String zhTip = tooltip(zh, prefix);
        Assert.assertFalse("en 文案不得为空: " + prefix, enTip.isEmpty());
        Assert.assertFalse("zh 文案不得为空: " + prefix, zhTip.isEmpty());
        Assert.assertNotEquals("中英必须各自本地化（不得直接复制同一文案）: " + prefix, enTip, zhTip);
    }

    private static String tooltip(String language, String prefix) {
        for (String line : language.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix + ".tooltip=")) {
                return trimmed.substring((prefix + ".tooltip=").length()).trim();
            }
            if (trimmed.startsWith(prefix + "=")) {
                return trimmed.substring((prefix + "=").length()).trim();
            }
        }
        return "";
    }

    private static String readResource(String name) throws Exception {
        InputStream stream = BackendDiagnosticsConfigContractTest.class.getClassLoader()
            .getResourceAsStream(name);
        Assert.assertNotNull("资源必须存在: " + name, stream);
        Reader reader = new InputStreamReader(stream, "UTF-8");
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            text.append(buffer, 0, read);
        }
        reader.close();
        return text.toString();
    }
}
