package club.heiqi.qz_miner.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
 * YAML 单权威启动器：严格 raw/语义预检、旧 cfg 一次导入与备份后默认恢复。
 *
 * <p>ValidatedSnapshot 仅是 Authority 的派生发布载荷，不写回 Authority/YAML，
 * 也不作为非法 Authority 的恢复源。</p>
 */
public final class ConfigBootstrap {

    public static final String YAML_FILE_NAME = "qz_miner.yaml";

    private static volatile ConfigManager manager;
    private static volatile File yamlFile;
    private static volatile ValidatedSnapshot currentValidatedSnapshot;
    private static volatile BackupCopier backupCopier = BackupCopier.DEFAULT;
    private static volatile CfgRetirer cfgRetirer = CfgRetirer.DEFAULT;

    private ConfigBootstrap() {
    }

    /** @return 当前 ConfigManager；未初始化返回 null */
    public static ConfigManager manager() {
        return manager;
    }

    /** @return 当前 YAML 文件；未初始化返回 null */
    public static File yamlFile() {
        return yamlFile;
    }

    /**
     * 获取当前已验证派生快照。
     *
     * @return 当前快照
     * @throws IllegalStateException 尚未初始化
     */
    public static ValidatedSnapshot currentValidatedSnapshot() {
        ValidatedSnapshot snapshot = currentValidatedSnapshot;
        if (snapshot == null) {
            throw new IllegalStateException("currentValidatedSnapshot is not initialized");
        }
        return snapshot;
    }

    /**
     * 仅当给定快照仍是 current 时执行一次受控短发布。
     *
     * <p>current 替换与 publication 共用 {@code ConfigBootstrap.class} monitor，避免异步任务在
     * check 与发布之间被更新快照穿透。action 只能做 Config 静态回灌、客户端状态更新或单向网络入队，
     * 不得等待外部线程，也不得触发新的配置提交。</p>
     *
     * @param snapshot 异步任务捕获的快照
     * @param action 受控短发布动作
     * @return 快照仍为 current 且已执行发布时为 true；陈旧任务为 false
     */
    public static synchronized boolean publishIfCurrent(ValidatedSnapshot snapshot, Runnable action) {
        if (snapshot == null || action == null) {
            throw new IllegalArgumentException("snapshot/action must not be null");
        }
        if (snapshot != currentValidatedSnapshot) {
            return false;
        }
        action.run();
        return true;
    }

    /**
     * 启动 YAML 权威。
     *
     * @param configDir Forge config 目录
     * @param legacyCfg 旧 cfg 一次导入源
     * @return 已严格验证的 manager
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
        if (isFile(yamlFile)) {
            StrictLoad existing = loadStrict(yamlFile, schema, "existing YAML");
            if (existing.isValid()) {
                commitManager(existing.manager, existing.snapshot);
                MyMod.LOG.info("Loaded YAML config authority: {}", yamlFile.getAbsolutePath());
                return manager;
            }
            MyMod.LOG.error("Existing YAML rejected; rebuilding defaults: {}", existing.error);
            requiredBackup(yamlFile, "invalid");
            requiredDelete(yamlFile, "invalid YAML");
            StrictLoad defaults = persistDefaultsStrict(yamlFile, schema, "invalid YAML recovery");
            commitManager(defaults.manager, defaults.snapshot);
            return manager;
        }

        if (isNonEmptyFile(legacyCfg)) {
            requiredBackup(legacyCfg, "pre-import");
            ImportResult imported = LegacyCfgImporter.importValues(legacyCfg);
            if (imported.status == ImportResult.Status.OK) {
                StrictLoad migration = migrateLegacy(yamlFile, schema, imported.values);
                if (migration.isValid()) {
                    retireCfgStrict(legacyCfg, "imported");
                    commitManager(migration.manager, migration.snapshot);
                    MyMod.LOG.info("Migrated legacy cfg to YAML: {} values -> {}",
                            Integer.valueOf(imported.values.size()), yamlFile.getAbsolutePath());
                    return manager;
                }
                MyMod.LOG.error("Legacy cfg migration rejected; rebuilding defaults: {}", migration.error);
            } else {
                MyMod.LOG.error("Legacy cfg import rejected ({}): {}", imported.status, imported.message);
            }

            cleanupPartialYamlIfAny(yamlFile);
            StrictLoad defaults = persistDefaultsStrict(yamlFile, schema, "legacy migration recovery");
            retireCfgStrict(legacyCfg, "import-failed");
            commitManager(defaults.manager, defaults.snapshot);
            return manager;
        }

        StrictLoad defaults = persistDefaultsStrict(yamlFile, schema, "first run defaults");
        commitManager(defaults.manager, defaults.snapshot);
        return manager;
    }

    /**
     * 成功 BATCH_SAVE 回调内同步捕获并替换当前派生快照。
     *
     * @param sourceManager 触发回调的 manager
     * @return 本次完整提交快照
     * @throws InternalError manager 身份或提交后语义不变量被破坏
     */
    public static synchronized ValidatedSnapshot captureCommittedSnapshot(ConfigManager sourceManager) {
        if (sourceManager == null || sourceManager != manager) {
            MyMod.LOG.error("BATCH_SAVE manager identity mismatch: expected={}, actual={}", manager, sourceManager);
            throw new InternalError("BATCH_SAVE manager identity mismatch");
        }
        ParseOutcome outcome;
        try {
            outcome = ConfigSemanticValidator.captureAndValidate(sourceManager);
        } catch (RuntimeException e) {
            MyMod.LOG.error("BATCH_SAVE committed snapshot capture failed", e);
            throw new InternalError("BATCH_SAVE committed snapshot capture failed", e);
        }
        if (!outcome.isValid()) {
            MyMod.LOG.error("BATCH_SAVE committed Authority violates DraftValidator: {}", outcome.result.summary());
            throw new InternalError("BATCH_SAVE committed Authority is invalid: " + outcome.result.summary());
        }
        currentValidatedSnapshot = outcome.snapshot;
        return outcome.snapshot;
    }

    /** 服务端启动只发布当前派生快照中的 general 字段。 */
    public static void reapplyGeneralOnServerStarting() {
        if (manager == null) {
            return;
        }
        ConfigValueBridge.applyGeneralFromSnapshot(currentValidatedSnapshot());
        MyMod.LOG.info("Applied current validated general config on serverStarting");
    }

    /** 测试钩子：重置静态持有。 */
    public static synchronized void resetForTests() {
        manager = null;
        yamlFile = null;
        currentValidatedSnapshot = null;
        backupCopier = BackupCopier.DEFAULT;
        cfgRetirer = CfgRetirer.DEFAULT;
    }

    static synchronized void setBackupCopierForTests(BackupCopier copier) {
        backupCopier = copier == null ? BackupCopier.DEFAULT : copier;
    }

    static synchronized void setCfgRetirerForTests(CfgRetirer retirer) {
        cfgRetirer = retirer == null ? CfgRetirer.DEFAULT : retirer;
    }

    private static StrictLoad migrateLegacy(File file, ConfigSchema schema, Map<String, Object> values) {
        try {
            ConfigManager migrationManager = newManager(file, schema);
            DraftBuffer draft = migrationManager.openDraft();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (schema.containsPath(entry.getKey())) {
                    draft.setDraft(entry.getKey(), entry.getValue());
                }
            }
            SaveOutcome outcome = migrationManager.save(draft);
            if (!outcome.isSuccess()) {
                return StrictLoad.failed("migration save " + outcome.status() + ": " + outcome.errorMessage());
            }
            if (!isNonEmptyFile(file)) {
                return StrictLoad.failed("migration save produced no YAML");
            }
            return loadStrict(file, schema, "migrated YAML");
        } catch (ConfigException e) {
            return StrictLoad.failed("migration bootstrap failed: " + message(e));
        } catch (RuntimeException e) {
            return StrictLoad.failed("migration failed: " + message(e));
        }
    }

    /** 持久化默认值，并对落盘结果执行 raw + 语义复验。 */
    static StrictLoad persistDefaultsStrict(File file, ConfigSchema schema, String reason) {
        MyMod.LOG.info("Persisting default YAML config ({}): {}", reason, file.getAbsolutePath());
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Failed to create parent for YAML: " + parent.getAbsolutePath());
        }
        try {
            ConfigManager created = newManager(file, schema);
            SaveOutcome outcome = created.save(created.openDraft());
            if (!outcome.isSuccess()) {
                throw new IllegalStateException("Failed to persist default YAML (" + reason + "): "
                        + outcome.status() + " " + outcome.errorMessage());
            }
        } catch (ConfigException e) {
            throw new IllegalStateException("Unable to create default ConfigManager (" + reason + ")", e);
        }
        if (!isNonEmptyFile(file)) {
            throw new IllegalStateException("Default YAML missing or empty after save (" + reason + ")");
        }
        StrictLoad reloaded = loadStrict(file, schema, "default rebuild: " + reason);
        if (!reloaded.isValid()) {
            throw new IllegalStateException("Default YAML failed raw/semantic revalidation (" + reason + "): "
                    + reloaded.error);
        }
        return reloaded;
    }

    private static StrictLoad loadStrict(File file, ConfigSchema schema, String reason) {
        try {
            RawYamlPreflight.Result raw = RawYamlPreflight.validate(file, schema);
            if (!raw.isValid()) {
                return StrictLoad.failed(reason + " raw preflight: " + raw.summary());
            }
            ConfigManager loaded = newManager(file, schema);
            ParseOutcome semantic = ConfigSemanticValidator.captureAndValidate(loaded);
            if (!semantic.isValid()) {
                return StrictLoad.failed(reason + " semantic validation: " + semantic.result.summary());
            }
            return StrictLoad.valid(loaded, semantic.snapshot);
        } catch (ConfigException e) {
            return StrictLoad.failed(reason + " syntax/read failure: " + message(e));
        } catch (RuntimeException e) {
            return StrictLoad.failed(reason + " bootstrap failure: " + message(e));
        }
    }

    private static ConfigManager newManager(File file, ConfigSchema schema) throws ConfigException {
        return ConfigManager.bootstrap(file, schema, ConfigSemanticValidator.draftValidator());
    }

    private static void commitManager(ConfigManager loaded, ValidatedSnapshot snapshot) {
        if (loaded == null || snapshot == null) {
            throw new IllegalArgumentException("loaded manager/snapshot must not be null");
        }
        manager = loaded;
        currentValidatedSnapshot = snapshot;
        ConfigValueBridge.applyAll(snapshot);
    }

    static void cleanupPartialYamlIfAny(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        requiredBackup(file, "partial");
        requiredDelete(file, "partial YAML");
    }

    /** 备份是删除与退役的硬前置；失败即抛出。 */
    static File requiredBackup(File file, String reason) {
        if (file == null || !file.exists()) {
            throw new IllegalStateException("Cannot backup missing file (" + reason + "): " + file);
        }
        File parent = file.getParentFile();
        String base = file.getName() + "." + System.currentTimeMillis() + "."
                + UUID.randomUUID().toString().substring(0, 8) + "." + reason + ".bak";
        File target = new File(parent, base);
        int index = 0;
        while (target.exists()) {
            target = new File(parent, base + "." + (++index));
        }
        try {
            backupCopier.copy(file, target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to backup config (" + reason + "): "
                    + file.getAbsolutePath() + " -> " + target.getAbsolutePath(), e);
        }
        if (!isNonEmptyFile(target)) {
            throw new IllegalStateException("Backup file empty or missing after copy: " + target.getAbsolutePath());
        }
        MyMod.LOG.info("Backed up config ({}): {} -> {}", reason, file.getAbsolutePath(), target.getAbsolutePath());
        return target;
    }

    static void requiredDelete(File file, String reason) {
        if (file != null && file.exists() && !file.delete()) {
            throw new IllegalStateException("Failed to delete " + reason + ": " + file.getAbsolutePath());
        }
    }

    private static void retireCfgStrict(File cfgFile, String reason) {
        if (cfgFile == null || !cfgFile.exists()) {
            return;
        }
        File target = new File(cfgFile.getParentFile(), cfgFile.getName() + "." + System.currentTimeMillis() + "."
                + UUID.randomUUID().toString().substring(0, 8) + "." + reason + ".imported.bak");
        try {
            cfgRetirer.move(cfgFile, target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to retire legacy cfg: " + cfgFile.getAbsolutePath(), e);
        }
        MyMod.LOG.info("Retired legacy cfg ({}): {} -> {}", reason,
                cfgFile.getAbsolutePath(), target.getAbsolutePath());
    }

    private static boolean isNonEmptyFile(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }

    private static boolean isFile(File file) {
        return file != null && file.isFile();
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isEmpty() ? error.getClass().getSimpleName() : value;
    }

    private static boolean samePath(File first, File second) {
        try {
            return first.getCanonicalFile().equals(second.getCanonicalFile());
        } catch (IOException e) {
            return first.getAbsolutePath().equals(second.getAbsolutePath());
        }
    }

    /** 备份复制小边界，供失败语义做确定性纯 JVM 测试。 */
    interface BackupCopier {
        BackupCopier DEFAULT = new BackupCopier() {
            @Override
            public void copy(File source, File target) throws IOException {
                Files.copy(source.toPath(), target.toPath());
            }
        };

        /** @param source 源文件 @param target 唯一备份目标 */
        void copy(File source, File target) throws IOException;
    }

    /** cfg 退役移动小边界，供提交前失败语义做确定性纯 JVM 测试。 */
    interface CfgRetirer {
        CfgRetirer DEFAULT = new CfgRetirer() {
            @Override
            public void move(File source, File target) throws IOException {
                Files.move(source.toPath(), target.toPath());
            }
        };

        /** @param source 旧 cfg @param target 唯一退役目标 */
        void move(File source, File target) throws IOException;
    }

    /** 严格加载结果。 */
    static final class StrictLoad {
        final ConfigManager manager;
        final ValidatedSnapshot snapshot;
        final String error;

        private StrictLoad(ConfigManager manager, ValidatedSnapshot snapshot, String error) {
            this.manager = manager;
            this.snapshot = snapshot;
            this.error = error;
        }

        static StrictLoad valid(ConfigManager manager, ValidatedSnapshot snapshot) {
            return new StrictLoad(manager, snapshot, null);
        }

        static StrictLoad failed(String error) {
            return new StrictLoad(null, null, error);
        }

        boolean isValid() {
            return manager != null && snapshot != null && error == null;
        }
    }
}
