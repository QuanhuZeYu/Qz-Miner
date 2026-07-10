package club.heiqi.qz_miner.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import club.heiqi.config.ConfigException;
import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.runtime.SaveOutcome;
import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.qz_miner.MyMod;

/**
 * 配置启动加载：YAML 权威、旧 cfg 一次性导入、坏文件备份与默认持久化。
 *
 * <p>server-safe：不引用 config.ui / McScreenBridge / LWJGL。</p>
 */
public final class ConfigBootstrap {

    /** 权威 YAML 文件名（位于 config 目录）。 */
    public static final String YAML_FILE_NAME = "qz_miner.yaml";

    private static volatile ConfigManager manager;
    private static volatile File yamlFile;

    private ConfigBootstrap() {
    }

    /**
     * @return 长寿命 ConfigManager；未初始化时为 null
     */
    public static ConfigManager manager() {
        return manager;
    }

    /**
     * @return 权威 YAML 文件；未初始化时为 null
     */
    public static File yamlFile() {
        return yamlFile;
    }

    /**
     * 启动加载配置权威。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>YAML 存在且可解析 → 直接 bootstrap</li>
     *   <li>YAML 存在但解析失败 → 时间戳备份后删除，再写默认 YAML</li>
     *   <li>YAML 不存在且 cfg 存在 → 导入后写 YAML，成功则 cfg 退役为 {@code .imported.bak}</li>
     *   <li>导入失败 / save INVALID/IO_FAILED → 备份 cfg、清理半成品、写默认 YAML</li>
     *   <li>YAML 与 cfg 皆无 → 持久化默认 YAML</li>
     * </ul>
     *
     * @param configDir Forge config 目录（通常为 {@code <mc>/config}）
     * @param legacyCfg Forge 建议的旧 cfg 路径（{@code event.getSuggestedConfigurationFile()}）
     * @return 已 bootstrap 的 ConfigManager
     */
    public static synchronized ConfigManager bootstrap(File configDir, File legacyCfg) {
        if (configDir == null) {
            throw new IllegalArgumentException("configDir must not be null");
        }
        if (!configDir.exists() && !configDir.mkdirs()) {
            MyMod.LOG.warn("Failed to create config directory: {}", configDir.getAbsolutePath());
        }

        yamlFile = new File(configDir, YAML_FILE_NAME);
        ConfigSchema schema = QzMinerConfigSchema.create();

        boolean yamlExists = yamlFile.isFile() && yamlFile.length() > 0;
        boolean cfgExists = legacyCfg != null && legacyCfg.isFile() && legacyCfg.length() > 0;

        if (yamlExists) {
            ConfigManager loaded = tryBootstrap(yamlFile, schema);
            if (loaded != null) {
                manager = loaded;
                ConfigValueBridge.applyFromAuthority(manager.authority());
                MyMod.LOG.info("Loaded YAML config authority: {}", yamlFile.getAbsolutePath());
                return manager;
            }
            // 坏 YAML：备份后删除再重建默认
            backupFile(yamlFile, "corrupt");
            if (!yamlFile.delete() && yamlFile.exists()) {
                MyMod.LOG.warn("Failed to delete corrupt YAML: {}", yamlFile.getAbsolutePath());
            }
            manager = persistDefaults(yamlFile, schema, "corrupt YAML recovery");
            ConfigValueBridge.applyFromAuthority(manager.authority());
            return manager;
        }

        if (cfgExists) {
            Map<String, Object> imported = LegacyCfgImporter.importValues(legacyCfg);
            if (imported.isEmpty()) {
                // 导入失败：退役 cfg，写默认
                retireCfg(legacyCfg, "import-failed");
                manager = persistDefaults(yamlFile, schema, "legacy cfg import failed");
                ConfigValueBridge.applyFromAuthority(manager.authority());
                return manager;
            }

            ConfigManager draftManager = createEmptyManager(yamlFile, schema);
            if (draftManager == null) {
                retireCfg(legacyCfg, "bootstrap-failed");
                manager = persistDefaults(yamlFile, schema, "empty manager after import");
                ConfigValueBridge.applyFromAuthority(manager.authority());
                return manager;
            }

            DraftBuffer draft = draftManager.openDraft();
            for (Map.Entry<String, Object> entry : imported.entrySet()) {
                if (schema.containsPath(entry.getKey())) {
                    draft.setDraft(entry.getKey(), entry.getValue());
                }
            }
            SaveOutcome outcome = draftManager.save(draft);
            if (outcome.isSuccess()) {
                retireCfg(legacyCfg, "imported");
                manager = draftManager;
                ConfigValueBridge.applyFromAuthority(manager.authority());
                MyMod.LOG.info("Migrated legacy cfg to YAML: {} values → {}",
                        Integer.valueOf(imported.size()), yamlFile.getAbsolutePath());
                return manager;
            }

            MyMod.LOG.warn("Legacy cfg migration save failed ({}): {}",
                    outcome.status(), outcome.errorMessage());
            cleanupPartialYaml(yamlFile);
            retireCfg(legacyCfg, "import-save-failed");
            manager = persistDefaults(yamlFile, schema, "legacy migration save failed");
            ConfigValueBridge.applyFromAuthority(manager.authority());
            return manager;
        }

        // 无 YAML 无 cfg
        manager = persistDefaults(yamlFile, schema, "first run defaults");
        ConfigValueBridge.applyFromAuthority(manager.authority());
        return manager;
    }

    /**
     * 测试钩子：重置静态持有，避免测试间泄漏。
     */
    public static synchronized void resetForTests() {
        manager = null;
        yamlFile = null;
    }

    private static ConfigManager tryBootstrap(File file, ConfigSchema schema) {
        try {
            return ConfigManager.bootstrap(file, schema);
        } catch (ConfigException e) {
            MyMod.LOG.error("YAML config parse failed: {}", file.getAbsolutePath(), e);
            return null;
        } catch (RuntimeException e) {
            MyMod.LOG.error("YAML config bootstrap failed: {}", file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 文件不存在时 bootstrap 得到 schema 默认权威；再 openDraft+save 持久化到盘。
     */
    private static ConfigManager persistDefaults(File file, ConfigSchema schema, String reason) {
        MyMod.LOG.info("Persisting default YAML config ({}): {}", reason, file.getAbsolutePath());
        ConfigManager created = createEmptyManager(file, schema);
        if (created == null) {
            throw new IllegalStateException("Unable to create default ConfigManager for " + file);
        }
        DraftBuffer draft = created.openDraft();
        SaveOutcome outcome = created.save(draft);
        if (!outcome.isSuccess()) {
            MyMod.LOG.error("Failed to persist default YAML ({}): status={} msg={}",
                    reason, outcome.status(), outcome.errorMessage());
            // 仍返回内存权威，保证运行时可读默认值
        }
        return created;
    }

    private static ConfigManager createEmptyManager(File file, ConfigSchema schema) {
        // 确保父目录存在；目标文件不存在时 Authority.load 使用 schema 默认
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            MyMod.LOG.warn("Failed to create parent for YAML: {}", parent.getAbsolutePath());
        }
        return tryBootstrap(file, schema);
    }

    private static void cleanupPartialYaml(File file) {
        if (file != null && file.exists()) {
            backupFile(file, "partial");
            if (!file.delete() && file.exists()) {
                MyMod.LOG.warn("Failed to delete partial YAML: {}", file.getAbsolutePath());
            }
        }
    }

    private static void retireCfg(File cfgFile, String reason) {
        if (cfgFile == null || !cfgFile.exists()) {
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File target = new File(cfgFile.getParentFile(),
                cfgFile.getName() + "." + stamp + "." + reason + ".imported.bak");
        try {
            Files.move(cfgFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            MyMod.LOG.info("Retired legacy cfg ({}): {} → {}", reason,
                    cfgFile.getAbsolutePath(), target.getAbsolutePath());
        } catch (IOException e) {
            MyMod.LOG.warn("Failed to retire legacy cfg, attempting rename: {}",
                    cfgFile.getAbsolutePath(), e);
            File fallback = new File(cfgFile.getParentFile(), cfgFile.getName() + ".imported.bak");
            if (!cfgFile.renameTo(fallback)) {
                MyMod.LOG.warn("Could not rename legacy cfg; leaving in place: {}",
                        cfgFile.getAbsolutePath());
            }
        }
    }

    private static void backupFile(File file, String reason) {
        if (file == null || !file.exists()) {
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File target = new File(file.getParentFile(),
                file.getName() + "." + stamp + "." + reason + ".bak");
        try {
            Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            MyMod.LOG.info("Backed up config ({}): {} → {}", reason,
                    file.getAbsolutePath(), target.getAbsolutePath());
        } catch (IOException e) {
            MyMod.LOG.warn("Failed to backup config file: {}", file.getAbsolutePath(), e);
        }
    }
}
