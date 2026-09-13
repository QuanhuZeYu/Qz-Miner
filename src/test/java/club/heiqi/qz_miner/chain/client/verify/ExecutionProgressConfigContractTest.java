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
import club.heiqi.qz_miner.config.ConfigSemanticValidator;
import club.heiqi.qz_miner.config.ConfigValueBridge;
import club.heiqi.qz_miner.config.QzMinerConfigDefaults;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;

/**
 * T43 波次 9 执行进度配置面契约（B5.2 / task-41/42）：五处同源 + 默认 off + 语言中英对称。
 *
 * <p>新键尚未落地时用反射访问，保证本探针可编译并给出**红断言**；键落地后自动转绿。
 * 五处口径：Schema（默认值表）/ Defaults / Config / Bridge / Validator 快照 + 语言资源。</p>
 */
public class ExecutionProgressConfigContractTest {

    private static final String SHORT_KEY = "clientPreviewExecutionProgress";
    private static final String CONFIG_PATH = "client.clientPreviewExecutionProgress";

    @Test
    public void configStaticFieldExistsAndDefaultsOff() throws Exception {
        Field field = Config.class.getField(SHORT_KEY);
        Assert.assertEquals("Config 字段必须是 boolean", boolean.class, field.getType());
        Assert.assertFalse("执行进度开关默认必须是 off", field.getBoolean(null));
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
        String prefix = "config.qz_miner." + SHORT_KEY;
        Assert.assertTrue("en_US 必须包含 " + prefix + " 条目", en.contains(prefix));
        Assert.assertTrue("zh_CN 必须包含 " + prefix + " 条目", zh.contains(prefix));

        String enTip = tooltip(en, prefix);
        String zhTip = tooltip(zh, prefix);
        Assert.assertFalse("en 提示不得为空", enTip.isEmpty());
        Assert.assertFalse("zh 提示不得为空", zhTip.isEmpty());
        Assert.assertNotEquals("中英必须各自本地化（不得直接复制同一文案）", enTip, zhTip);
    }

    /**
     * 端到端同源：YAML 草稿写入 true → 校验（Schema 必须声明该键）→ 桥接 → Config 静态字段变 true。
     * 覆盖 Schema / Validator / Bridge 三处，与前面的 Config / Defaults / 语言四处合起来为五处同源。
     */
    @Test
    public void draftSaveRoundTripBridgesTheKeyIntoConfig() throws Exception {
        boolean saved = Config.clientPreviewExecutionProgress;
        File yaml = File.createTempFile("qz-execution-progress", ".yaml");
        try {
            ConfigManager manager = ConfigManager.bootstrap(
                yaml, QzMinerConfigSchema.create(), ConfigSemanticValidator.draftValidator());
            Assert.assertFalse("初始必须为 off", Config.clientPreviewExecutionProgress);
            DraftBuffer draft = manager.openDraft();
            draft.setDraft(CONFIG_PATH, Boolean.TRUE);
            club.heiqi.config.runtime.SaveOutcome outcome = manager.save(draft);
            Assert.assertEquals("合法键必须保存成功（Schema 已声明）: " + outcome.status(),
                club.heiqi.config.runtime.SaveOutcome.Status.OK, outcome.status());
            // 生产提交路径 = 捕获已提交快照 → 桥接写回静态字段（与 ConfigBootstrap 分发一致）
            ConfigSemanticValidator.ValidatedSnapshot enabled =
                ConfigSemanticValidator.captureAndValidate(manager).snapshot;
            Assert.assertTrue("YAML true 必须解析进快照", enabled.clientPreviewExecutionProgress);
            ConfigValueBridge.applyClientFromSnapshot(enabled);
            Assert.assertTrue("桥接必须把 true 写进 Config", Config.clientPreviewExecutionProgress);

            DraftBuffer offDraft = manager.openDraft();
            offDraft.setDraft(CONFIG_PATH, Boolean.FALSE);
            manager.save(offDraft);
            ConfigSemanticValidator.ValidatedSnapshot disabled =
                ConfigSemanticValidator.captureAndValidate(manager).snapshot;
            Assert.assertFalse("YAML false 必须解析进快照", disabled.clientPreviewExecutionProgress);
            ConfigValueBridge.applyClientFromSnapshot(disabled);
            Assert.assertFalse("恢复默认必须写回 off", Config.clientPreviewExecutionProgress);
        } finally {
            Config.clientPreviewExecutionProgress = saved;
            yaml.delete();
        }
    }

    private static String tooltip(String language, String prefix) {
        for (String line : language.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix + ".tooltip=")) {
                return trimmed.substring((prefix + ".tooltip=").length()).trim();
            }
        }
        return "";
    }

    private static String readResource(String name) throws Exception {
        InputStream stream = ExecutionProgressConfigContractTest.class.getClassLoader()
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
