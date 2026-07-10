package club.heiqi.qz_miner.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

import club.heiqi.config.ConfigException;
import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.runtime.SaveOutcome;
import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ParseOutcome;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.qz_miner.config.LegacyCfgImporter.ImportResult;

/**
 * 配置启动加载：YAML 权威、旧 cfg 一次性导入、坏文件备份与默认持久化。
 *
 * <p>磁盘事务 fail-fast：备份成功是删除/退役的前置；默认 YAML save 非 OK 或语义校验失败禁止启动。
 * manager 仅最终验证后一次赋值；同路径幂等，不同路径二次调用 fail-fast。</p>
 *
 * <p>server-safe：不引用 config.ui / McScreenBridge / LWJGL。</p>
 */
public final class ConfigBootstrap {

    public static final String YAML_FILE_NAME = "qz_miner.yaml";

    private static volatile ConfigManager manager;
    private static volatile File yamlFile;
    private static volatile ValidatedSnapshot lastValidSnapshot;

    private ConfigBootstrap() {
    }

    public static ConfigManager manager() {
        return manager;
    }

    public static File yamlFile() {
        return yamlFile;
    }

    /**
     * @return 最近一次严格校验通过的快照；未初始化为 null
     */
    public static ValidatedSnapshot lastValidSnapshot() {
        return lastValidSnapshot;
    }

    /**
     * 启动加载配置权威。
     *
     * @param configDir Forge config 目录
     * @param legacyCfg 旧 Forge cfg，仅一次性导入
     * @return 已验证的 ConfigManager
     * @throws IllegalStateException 无法保障源数据备份或默认 YAML 落盘/校验
     */
    public static synchronized ConfigManager bootstrap(File configDir, File legacyCfg) {
        if (configDir == null) {
            throw new IllegalArgumentException("configDir must not be null");
        }
        File targetYaml = new File(configDir, YAML_FILE_NAME);
        if (manager != null) {
            if (yamlFile != null && samePath(yamlFile, targetYaml)) {
                return manager;
            }
            throw new IllegalStateException("ConfigBootstrap already initialized for "
                    + yamlFile + "; cannot re-bootstrap for " + targetYaml);
        }

        if (!configDir.exists() && !configDir.mkdirs()) {
            throw new IllegalStateException("Failed to create config directory: " + configDir.getAbsolutePath());
        }

        yamlFile = targetYaml;
        ConfigSchema schema = QzMinerConfigSchema.create();

        boolean yamlExists = yamlFile.isFile() && yamlFile.length() > 0;
        boolean cfgExists = legacyCfg != null && legacyCfg.isFile() && legacyCfg.length() > 0;

        if (yamlExists) {
            ConfigManager loaded = tryBootstrap(yamlFile, schema);
            if (loaded != null) {
                ValidatedSnapshot snap = requireValidSnapshot(loaded, "existing YAML");
                commitManager(loaded, snap);
                MyMod.LOG.info("Loaded YAML config authority: {}", yamlFile.getAbsolutePath());
                return manager;
            }
            // 坏 YAML：required copy → required delete → 默认重建
            requiredBackup(yamlFile, "corrupt");
            requiredDelete(yamlFile, "corrupt YAML");
            ConfigManager defaults = persistDefaultsStrict(yamlFile, schema, "corrupt YAML recovery");
            ValidatedSnapshot snap = requireValidSnapshot(defaults, "corrupt recovery defaults");
            commitManager(defaults, snap);
            return manager;
        }

        if (cfgExists) {
            // 导入前必须先备份原 cfg
            requiredBackup(legacyCfg, "pre-import");
            ImportResult importResult = LegacyCfgImporter.importValues(legacyCfg);
            if (importResult.status != ImportResult.Status.OK) {
                MyMod.LOG.warn("Legacy cfg import status={}: {}", importResult.status, importResult.message);
                cleanupPartialYamlIfAny(yamlFile);
                ConfigManager defaults = persistDefaultsStrict(yamlFile, schema, "legacy cfg import " + importResult.status);
                ValidatedSnapshot snap = requireValidSnapshot(defaults, "import-failed defaults");
                commitManager(defaults, snap);
                // 原 cfg 已备份；可退役避免反复导入（YAML 已存在下次直接走 YAML）
                tryRetireCfg(legacyCfg, "import-" + importResult.status.name().toLowerCase());
                return manager;
            }

            ConfigManager draftManager = createEmptyManager(yamlFile, schema);
            if (draftManager == null) {
                cleanupPartialYamlIfAny(yamlFile);
                ConfigManager defaults = persistDefaultsStrict(yamlFile, schema, "empty manager after import");
                ValidatedSnapshot snap = requireValidSnapshot(defaults, "bootstrap-failed defaults");
                commitManager(defaults, snap);
                tryRetireCfg(legacyCfg, "bootstrap-failed");
                return manager;
            }

            DraftBuffer draft = draftManager.openDraft();
            for (Map.Entry<String, Object> entry : importResult.values.entrySet()) {
                if (schema.containsPath(entry.getKey())) {
                    draft.setDraft(entry.getKey(), entry.getValue());
                }
            }
            SaveOutcome outcome = draftManager.save(draft);
            if (outcome.isSuccess() && yamlFile.isFile() && yamlFile.length() > 0) {
                ConfigManager reloaded = tryBootstrap(yamlFile, schema);
                if (reloaded != null) {
                    try {
                        ValidatedSnapshot snap = requireValidSnapshot(reloaded, "imported YAML");
                        commitManager(reloaded, snap);
                        tryRetireCfg(legacyCfg, "imported");
                        MyMod.LOG.info("Migrated legacy cfg to YAML: {} values → {}",
                                Integer.valueOf(importResult.values.size()), yamlFile.getAbsolutePath());
                        return manager;
                    } catch (IllegalStateException semantic) {
                        MyMod.LOG.warn("Imported YAML failed semantic validation: {}", semantic.getMessage());
                    }
                }
            } else {
                MyMod.LOG.warn("Legacy cfg migration save failed ({}): {}",
                        outcome.status(), outcome.errorMessage());
            }

            cleanupPartialYamlIfAny(yamlFile);
            ConfigManager defaults = persistDefaultsStrict(yamlFile, schema, "legacy migration save/validate failed");
            ValidatedSnapshot snap = requireValidSnapshot(defaults, "migration-failed defaults");
            commitManager(defaults, snap);
            tryRetireCfg(legacyCfg, "import-save-failed");
            return manager;
        }

        // 无 YAML 无 cfg
        ConfigManager defaults = persistDefaultsStrict(yamlFile, schema, "first run defaults");
        ValidatedSnapshot snap = requireValidSnapshot(defaults, "first run defaults");
        commitManager(defaults, snap);
        return manager;
    }

    /**
     * 服务端启动时从当前 Authority 再发布 general（集成服在主菜单保存后启动）。
     */
    public static void reapplyGeneralOnServerStarting() {
        ValidatedSnapshot snap = lastValidSnapshot;
        ConfigManager m = manager;
        if (m == null) {
            return;
        }
        ParseOutcome outcome = ConfigSemanticValidator.parseAndValidate(m.authority());
        if (!outcome.isValid()) {
            MyMod.LOG.error("Authority invalid at serverStarting; keeping lastValid general: {}",
                    outcome.result.summary());
            if (snap != null) {
                ConfigValueBridge.applyGeneralFromSnapshot(snap);
            }
            return;
        }
        lastValidSnapshot = outcome.snapshot;
        ConfigValueBridge.applyGeneralFromSnapshot(outcome.snapshot);
        MyMod.LOG.info("Re-applied general config from Authority on serverStarting");
    }

    /**
     * 更新 last-valid 快照（BATCH_SAVE 成功路径）。
     */
    public static synchronized void updateLastValidSnapshot(ValidatedSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        lastValidSnapshot = snapshot;
    }

    /**
     * 测试钩子：重置静态持有。
     */
    public static synchronized void resetForTests() {
        manager = null;
        yamlFile = null;
        lastValidSnapshot = null;
    }

    private static void commitManager(ConfigManager loaded, ValidatedSnapshot snap) {
        ConfigValueBridge.applyAll(snap);
        lastValidSnapshot = snap;
        manager = loaded;
    }

    private static ValidatedSnapshot requireValidSnapshot(ConfigManager loaded, String reason) {
        ParseOutcome outcome = ConfigSemanticValidator.parseAndValidate(loaded.authority());
        if (!outcome.isValid()) {
            throw new IllegalStateException("Config semantic validation failed (" + reason + "): "
                    + outcome.result.summary());
        }
        return outcome.snapshot;
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
     * 持久化 schema 默认并严格复验落盘文件；任一失败 fail-fast。
     */
    static ConfigManager persistDefaultsStrict(File file, ConfigSchema schema, String reason) {
        MyMod.LOG.info("Persisting default YAML config ({}): {}", reason, file.getAbsolutePath());
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Failed to create parent for YAML: " + parent.getAbsolutePath());
        }
        ConfigManager created = tryBootstrap(file, schema);
        if (created == null) {
            throw new IllegalStateException("Unable to create default ConfigManager for " + file + " (" + reason + ")");
        }
        DraftBuffer draft = created.openDraft();
        SaveOutcome outcome = created.save(draft);
        if (!outcome.isSuccess()) {
            throw new IllegalStateException("Failed to persist default YAML (" + reason + "): status="
                    + outcome.status() + " msg=" + outcome.errorMessage());
        }
        if (!file.isFile() || file.length() <= 0) {
            throw new IllegalStateException("Default YAML missing or empty after save (" + reason + "): "
                    + file.getAbsolutePath());
        }
        // 重新 bootstrap 复验可解析
        ConfigManager reloaded = tryBootstrap(file, schema);
        if (reloaded == null) {
            throw new IllegalStateException("Default YAML not re-loadable after save (" + reason + "): "
                    + file.getAbsolutePath());
        }
        return reloaded;
    }

    private static ConfigManager createEmptyManager(File file, ConfigSchema schema) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Failed to create parent for YAML: " + parent.getAbsolutePath());
        }
        return tryBootstrap(file, schema);
    }

    private static void cleanupPartialYamlIfAny(File file) {
        if (file != null && file.exists()) {
            try {
                requiredBackup(file, "partial");
            } catch (IllegalStateException e) {
                MyMod.LOG.warn("Could not backup partial YAML before delete: {}", e.getMessage());
            }
            if (!file.delete() && file.exists()) {
                throw new IllegalStateException("Failed to delete partial YAML: " + file.getAbsolutePath());
            }
        }
    }

    /**
     * 唯一备份名：毫秒 + 短 UUID；冲突则递增后缀。禁止 REPLACE 覆盖历史备份。
     * 备份失败抛异常（删除/退役前置）。
     */
    static File requiredBackup(File file, String reason) {
        if (file == null || !file.exists()) {
            throw new IllegalStateException("Cannot backup missing file (" + reason + "): " + file);
        }
        File parent = file.getParentFile();
        String base = file.getName() + "." + System.currentTimeMillis() + "."
                + UUID.randomUUID().toString().substring(0, 8) + "." + reason + ".bak";
        File target = new File(parent, base);
        int n = 0;
        while (target.exists()) {
            n++;
            target = new File(parent, base + "." + n);
        }
        try {
            Files.copy(file.toPath(), target.toPath());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to backup config (" + reason + "): "
                    + file.getAbsolutePath() + " → " + target.getAbsolutePath(), e);
        }
        if (!target.isFile() || target.length() <= 0) {
            throw new IllegalStateException("Backup file empty or missing after copy: " + target.getAbsolutePath());
        }
        MyMod.LOG.info("Backed up config ({}): {} → {}", reason, file.getAbsolutePath(), target.getAbsolutePath());
        return target;
    }

    static void requiredDelete(File file, String reason) {
        if (file == null) {
            return;
        }
        if (!file.exists()) {
            return;
        }
        if (!file.delete()) {
            throw new IllegalStateException("Failed to delete " + reason + ": " + file.getAbsolutePath());
        }
    }

    /**
     * 退役 cfg（已备份后尽力移动；失败只 warn，因备份已存在）。
     */
    static void tryRetireCfg(File cfgFile, String reason) {
        if (cfgFile == null || !cfgFile.exists()) {
            return;
        }
        File parent = cfgFile.getParentFile();
        String base = cfgFile.getName() + "." + System.currentTimeMillis() + "."
                + UUID.randomUUID().toString().substring(0, 8) + "." + reason + ".imported.bak";
        File target = new File(parent, base);
        int n = 0;
        while (target.exists()) {
            n++;
            target = new File(parent, base + "." + n);
        }
        try {
            Files.move(cfgFile.toPath(), target.toPath());
            MyMod.LOG.info("Retired legacy cfg ({}): {} → {}", reason,
                    cfgFile.getAbsolutePath(), target.getAbsolutePath());
        } catch (IOException e) {
            MyMod.LOG.warn("Failed to retire legacy cfg (backup already exists): {}",
                    cfgFile.getAbsolutePath(), e);
        }
    }

    private static boolean samePath(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }
}
