package club.heiqi.qz_miner.chain.client.verify;

import java.io.File;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

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
import club.heiqi.qz_miner.testsupport.LanguageFiles;

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
        assertSymmetricTooltip("config.qz_miner." + SHORT_KEY);
    }

    /** HUD 行文案（标签 + 回退片段）中英必须都在，且不得直接复制同一文案。 */
    @Test
    public void hudLanguageEntriesArePresentInBothLanguages() throws Exception {
        assertSymmetricTooltip("hud.qz_miner.preview.backend.label");
        assertSymmetricTooltip("hud.qz_miner.preview.backend.fallback");
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

    /**
     * 语言条目契约：两份 lang 都必须<strong>解析后按键</strong>命中该键，且各自有非空文案。
     *
     * <p>旧写法对整份 lang 文本做 {@code contains(prefix)}：键名多一截后缀、或前缀恰好出现在
     * 别的键的值里都会误判通过；现在解析成 {@link Properties} 后按 key 取。值的具体措辞不作断言
     * （措辞不是代码回归防线），但「英文原文被复制进中文」这条会红。</p>
     */
    private static void assertSymmetricTooltip(String prefix) throws Exception {
        Properties en = LanguageFiles.read("assets/qz_miner/lang/en_US.lang");
        Properties zh = LanguageFiles.read("assets/qz_miner/lang/zh_CN.lang");
        String enTip = tooltip(en, prefix);
        String zhTip = tooltip(zh, prefix);
        Assert.assertFalse("en_US 必须提供 " + prefix + " 条目", enTip.isEmpty());
        Assert.assertFalse("zh_CN 必须提供 " + prefix + " 条目", zhTip.isEmpty());
        Assert.assertNotEquals("中英必须各自本地化（不得直接复制同一文案）: " + prefix, enTip, zhTip);
    }

    /** 取条目文案：接受 {@code prefix} 与 {@code prefix.tooltip} 两种落点；键缺失返回空串。 */
    private static String tooltip(Properties language, String prefix) {
        String value = language.getProperty(prefix + ".tooltip");
        if (value == null) {
            value = language.getProperty(prefix);
        }
        return value == null ? "" : value.trim();
    }
}
