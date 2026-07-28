package club.heiqi.qz_miner.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final AtomicLong COMMIT_EPOCH = new AtomicLong();
    private static volatile CommittedSnapshot currentCommittedSnapshot;
    private static volatile BackupCopier backupCopier = BackupCopier.DEFAULT;
    private static volatile CfgRetirer cfgRetirer = CfgRetirer.DEFAULT;
    private static volatile DefaultPersister defaultPersister = DefaultPersister.DEFAULT;

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
        return currentCommittedSnapshot().snapshot;
    }

    /**
     * 获取当前提交令牌。
     *
     * @return 当前不可变提交包装
     * @throws IllegalStateException 尚未初始化
     */
    public static CommittedSnapshot currentCommittedSnapshot() {
        CommittedSnapshot committed = currentCommittedSnapshot;
        if (committed == null) {
            throw new IllegalStateException("currentValidatedSnapshot is not initialized");
        }
        return committed;
    }

    /**
     * 无锁检查提交令牌是否仍为全局 current。
     *
     * @param committed 待检查令牌
     * @return identity 仍为 current 时为 true
     */
    public static boolean isCurrent(CommittedSnapshot committed) {
        return committed != null && committed == currentCommittedSnapshot;
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

        ConfigSchema schema = QzMinerConfigSchema.create();
        if (isFile(targetYaml)) {
            StrictLoad existing = loadStrict(targetYaml, schema, "existing YAML");
            if (existing.isValid()) {
                commitManager(targetYaml, existing.manager, existing.snapshot);
                MyMod.LOG.info("Loaded YAML config authority: {}", targetYaml.getAbsolutePath());
                return manager;
            }
            MyMod.LOG.error("Existing YAML rejected; rebuilding defaults: {}", existing.error);
            requiredBackup(targetYaml, "invalid");
            requiredDelete(targetYaml, "invalid YAML");
            StrictLoad defaults = persistDefaults(targetYaml, schema, "invalid YAML recovery");
            commitManager(targetYaml, defaults.manager, defaults.snapshot);
            return manager;
        }

        if (isNonEmptyFile(legacyCfg)) {
            requiredBackup(legacyCfg, "pre-import");
            ImportResult imported = LegacyCfgImporter.importValues(legacyCfg);
            if (imported.status == ImportResult.Status.OK) {
                StrictLoad migration = migrateLegacy(targetYaml, schema, imported.values);
                if (migration.isValid()) {
                    retireCfgStrict(legacyCfg, "imported");
                    commitManager(targetYaml, migration.manager, migration.snapshot);
                    MyMod.LOG.info("Migrated legacy cfg to YAML: {} values -> {}",
                            Integer.valueOf(imported.values.size()), targetYaml.getAbsolutePath());
                    return manager;
                }
                MyMod.LOG.error("Legacy cfg migration rejected; rebuilding defaults: {}", migration.error);
            } else {
                MyMod.LOG.error("Legacy cfg import rejected ({}): {}", imported.status, imported.message);
            }

            cleanupPartialYamlIfAny(targetYaml);
            StrictLoad defaults = persistDefaults(targetYaml, schema, "legacy migration recovery");
            retireCfgStrict(legacyCfg, "import-failed");
            commitManager(targetYaml, defaults.manager, defaults.snapshot);
            return manager;
        }

        StrictLoad defaults = persistDefaults(targetYaml, schema, "first run defaults");
        commitManager(targetYaml, defaults.manager, defaults.snapshot);
        return manager;
    }

    /**
     * 成功 BATCH_SAVE/RELOAD 通知内同步捕获并替换当前派生快照。
     *
     * @param sourceManager 触发回调的 manager
     * @return 本次完整提交令牌
     * @throws ConfigAuthorityInvariantError manager 身份或提交后语义不变量被破坏
     */
    public static synchronized CommittedSnapshot captureCommittedSnapshot(ConfigManager sourceManager) {
        if (sourceManager == null || sourceManager != manager) {
            MyMod.LOG.error("Config change manager identity mismatch: expected={}, actual={}", manager, sourceManager);
            throw new ConfigAuthorityInvariantError("Config change manager identity mismatch");
        }
        ParseOutcome outcome;
        try {
            outcome = ConfigSemanticValidator.captureAndValidate(sourceManager);
        } catch (RuntimeException e) {
            MyMod.LOG.error("Config change committed snapshot capture failed", e);
            throw new ConfigAuthorityInvariantError("Config change committed snapshot capture failed", e);
        }
        if (!outcome.isValid()) {
            MyMod.LOG.error("Config change committed Authority violates DraftValidator: {}", outcome.result.summary());
            throw new ConfigAuthorityInvariantError(
                    "Config change committed Authority is invalid: " + outcome.result.summary());
        }
        CommittedSnapshot committed = newCommittedSnapshot(outcome.snapshot);
        currentCommittedSnapshot = committed;
        return committed;
    }

    /**
     * 成功提交后复用同步 listener 已捕获的 current；没有 listener 捕获时才补一次捕获。
     *
     * @param sourceManager 完成 save/reload 的 manager
     * @param before 提交前的 current identity
     * @return 对应成功提交的令牌
     */
    public static synchronized CommittedSnapshot currentOrCaptureAfterCommit(
            ConfigManager sourceManager, CommittedSnapshot before) {
        if (sourceManager == null || sourceManager != manager) {
            throw new ConfigAuthorityInvariantError("Config change manager identity mismatch");
        }
        CommittedSnapshot current = currentCommittedSnapshot;
        if (current != null && current != before) {
            return current;
        }
        return captureCommittedSnapshot(sourceManager);
    }

    /** 服务端启动只发布当前派生快照中的 general 字段。 */
    public static void reapplyGeneralOnServerStarting() {
        if (manager == null) {
            return;
        }
        ConfigValueBridge.applyGeneralFromSnapshot(currentValidatedSnapshot());
        MyMod.LOG.info("Applied current validated general config on serverStarting");
    }

    /** 测试钩子：将 commit epoch 设为接近溢出，供零发布失败测试。不回退单调性。 */
    static synchronized void setCommitEpochForTests(long epoch) {
        if (epoch < 0L) {
            throw new IllegalArgumentException("epoch must be non-negative");
        }
        COMMIT_EPOCH.set(epoch);
    }

    /** 测试钩子：重置静态持有（不回退 epoch 单调性）。 */
    public static synchronized void resetForTests() {
        manager = null;
        yamlFile = null;
        currentCommittedSnapshot = null;
        backupCopier = BackupCopier.DEFAULT;
        cfgRetirer = CfgRetirer.DEFAULT;
        defaultPersister = DefaultPersister.DEFAULT;
    }

    static synchronized void setBackupCopierForTests(BackupCopier copier) {
        backupCopier = copier == null ? BackupCopier.DEFAULT : copier;
    }

    static synchronized void setCfgRetirerForTests(CfgRetirer retirer) {
        cfgRetirer = retirer == null ? CfgRetirer.DEFAULT : retirer;
    }

    static synchronized void setDefaultPersisterForTests(DefaultPersister persister) {
        defaultPersister = persister == null ? DefaultPersister.DEFAULT : persister;
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

    private static StrictLoad persistDefaults(File file, ConfigSchema schema, String reason) {
        return defaultPersister.persist(file, schema, reason);
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

    private static void commitManager(File targetYaml, ConfigManager loaded, ValidatedSnapshot snapshot) {
        if (targetYaml == null || loaded == null || snapshot == null) {
            throw new IllegalArgumentException("target YAML/loaded manager/snapshot must not be null");
        }
        // 先构造可能失败的 CommittedSnapshot（epoch overflow），再 applyAll/发布 yaml/current/manager
        CommittedSnapshot committed = newCommittedSnapshot(snapshot);
        ConfigValueBridge.applyAll(snapshot);
        yamlFile = targetYaml;
        currentCommittedSnapshot = committed;
        manager = loaded;
    }

    private static CommittedSnapshot newCommittedSnapshot(ValidatedSnapshot snapshot) {
        long previous = COMMIT_EPOCH.get();
        if (previous == Long.MAX_VALUE) {
            throw new ConfigAuthorityInvariantError("Config commit epoch overflow");
        }
        long epoch = COMMIT_EPOCH.incrementAndGet();
        if (epoch <= 0L) {
            throw new ConfigAuthorityInvariantError("Config commit epoch overflow");
        }
        return new CommittedSnapshot(epoch, snapshot);
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

    /** 默认恢复小边界，供失败事务做确定性纯 JVM 测试。 */
    interface DefaultPersister {
        DefaultPersister DEFAULT = new DefaultPersister() {
            @Override
            public StrictLoad persist(File file, ConfigSchema schema, String reason) {
                return ConfigBootstrap.persistDefaultsStrict(file, schema, reason);
            }
        };

        /** @return 已落盘并复验的严格加载结果 */
        StrictLoad persist(File file, ConfigSchema schema, String reason);
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
