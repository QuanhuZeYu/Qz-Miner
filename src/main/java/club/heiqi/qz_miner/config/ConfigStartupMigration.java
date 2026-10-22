package club.heiqi.qz_miner.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.config.LegacyCfgImporter.ImportResult;
import club.heiqi.uilib.ui.config.ConfigCodec;
import club.heiqi.uilib.ui.config.ConfigCodecs;
import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigException;
import club.heiqi.uilib.ui.config.ConfigKey;
import club.heiqi.uilib.ui.config.ConfigSchema;

/** 仅启动时使用：旧 cfg 导入、旧预算收敛、无效 YAML 的备份恢复。 */
final class ConfigStartupMigration {
    private static final ConfigCodec<Double> OPTIONAL_NUMBER = ConfigCodecs.nullable(ConfigCodecs.DOUBLE);
    // 旧预算已经退役，非法旧值按历史规则回默认；普通业务键仍使用严格 codec。
    private static final ConfigCodec<Double> LEGACY_NUMBER = new ConfigCodec<Double>() {
        public Double read(Object value, String path) {
            if (!(value instanceof Number)) return null;
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) ? number : null;
        }
        public Object write(Double value) { return OPTIONAL_NUMBER.write(value); }
        public Double snapshot(Double value) { return OPTIONAL_NUMBER.snapshot(value); }
    };
    private static final ConfigCodec<Double> MISSING_TICK_BUDGET = new ConfigCodec<Double>() {
        // 缺键通过 default null 表达；显式 YAML null 仍须由 DOUBLE.read 拒绝。
        public Double read(Object value, String path) { return ConfigCodecs.DOUBLE.read(value, path); }
        public Object write(Double value) { return OPTIONAL_NUMBER.write(value); }
        public Double snapshot(Double value) { return OPTIONAL_NUMBER.snapshot(value); }
    };
    private static final ConfigKey<Double> OPTIONAL_TICK_BUDGET = new ConfigKey<Double>(
            "general.tickBudgetMs", MISSING_TICK_BUDGET, null,
            value -> value == null || value >= QzMinerConfigDefaults.TICK_BUDGET_MIN_MS
                    && value <= QzMinerConfigDefaults.TICK_BUDGET_MAX_MS && value == Math.rint(value));
    private static final ConfigKey<Double> LEGACY_DURATION = legacy("general.parallelTickMinDurationMs");
    private static final ConfigKey<Double> LEGACY_MAX_BREAK = legacy("general.maxBreakPerTick");
    private static final ConfigKey<Double> LEGACY_SERVER_UNITS = legacy("general.parallelTickServerWorkBudgetUnits");
    private static final ConfigKey<Double> LEGACY_CLIENT_UNITS = legacy("client.parallelTickClientWorkBudgetUnits");
    private static final ConfigSchema LEGACY_SCHEMA = migrationSchema();

    private ConfigStartupMigration() { }

    static ConfigDocument load(File yaml, File legacyCfg) throws IOException {
        if (yaml.exists()) {
            if (!yaml.isFile()) throw new IOException("配置路径不是普通文件: " + yaml);
            try {
                return readStrict(yaml);
            } catch (ConfigException invalid) {
                if (isLegacyBudgetPath(invalid.path())) {
                    ConfigDocument migrated;
                    try {
                        migrated = migrateBudget(yaml);
                    } catch (ConfigException invalidMigration) {
                        return recoverInvalid(yaml, invalidMigration);
                    }
                    // 合法业务候选的备份/写入失败必须传播，禁止误当无效配置回退默认。
                    backup(yaml, "pre-5.3-budget-migration");
                    ConfigStorage.write(yaml.toPath(), migrated);
                    return migrated;
                }
                return recoverInvalid(yaml, invalid);
            }
        }
        ConfigDocument candidate = ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA);
        if (legacyCfg != null && legacyCfg.isFile() && legacyCfg.length() > 0L) {
            backup(legacyCfg, "pre-import");
            ImportResult imported = LegacyCfgImporter.importValues(legacyCfg);
            String reason = "import-failed";
            if (imported.status == ImportResult.Status.OK) {
                try {
                    Map<ConfigKey<?>, Object> updates = new LinkedHashMap<ConfigKey<?>, Object>();
                    for (Map.Entry<String, Object> entry : imported.values.entrySet()) {
                        updates.put(QzMinerConfigSchema.key(entry.getKey()), entry.getValue());
                    }
                    candidate = candidate.withAll(updates);
                    ConfigSemanticValidator.requireValid(candidate);
                    reason = "imported";
                } catch (RuntimeException invalid) {
                    MyMod.LOG.error("Legacy cfg migration rejected; restoring defaults", invalid);
                    candidate = ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA);
                }
            }
            ConfigSemanticValidator.requireValid(candidate);
            ConfigStorage.write(yaml.toPath(), candidate);
            // 退役失败时 YAML 已完整可读，但权威尚未发布；下次启动优先使用 YAML。
            Files.move(legacyCfg.toPath(), backupTarget(legacyCfg, reason + ".imported").toPath());
            return candidate;
        }
        ConfigSemanticValidator.requireValid(candidate);
        ConfigStorage.write(yaml.toPath(), candidate);
        return candidate;
    }

    static ConfigDocument readStrict(File yaml) throws IOException {
        ConfigDocument document = ConfigDocument.read(QzMinerConfigSchema.SCHEMA, yaml.toPath());
        ConfigSemanticValidator.requireValid(document);
        return document;
    }

    private static ConfigDocument recoverInvalid(File yaml, ConfigException invalid) throws IOException {
        MyMod.LOG.error("Existing YAML rejected; rebuilding defaults: {}", invalid.getMessage());
        backup(yaml, "invalid");
        ConfigDocument defaults = ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA);
        ConfigSemanticValidator.requireValid(defaults);
        // 备份完成后原子替换；恢复写入失败也保留原文件，不提前删除。
        ConfigStorage.write(yaml.toPath(), defaults);
        return defaults;
    }

    private static ConfigDocument migrateBudget(File yaml) throws IOException {
        ConfigDocument previous = ConfigDocument.read(LEGACY_SCHEMA, yaml.toPath());
        Map<ConfigKey<?>, Object> updates = new LinkedHashMap<ConfigKey<?>, Object>();
        for (ConfigKey<?> key : QzMinerConfigSchema.SCHEMA.keys()) {
            if (key != QzMinerConfigSchema.TICK_BUDGET_MS) updates.put(key, previous.get(key));
        }
        Double explicit = previous.get(OPTIONAL_TICK_BUDGET);
        Double duration = previous.get(LEGACY_DURATION);
        double value = explicit != null ? explicit : duration == null
                ? QzMinerConfigDefaults.TICK_BUDGET_MS : normalizeLegacyTickDuration(duration);
        updates.put(QzMinerConfigSchema.TICK_BUDGET_MS, value);
        ConfigDocument migrated = ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA).withAll(updates);
        ConfigSemanticValidator.requireValid(migrated);
        return migrated;
    }

    static int normalizeLegacyTickDuration(double value) {
        if (!Double.isFinite(value) || value != Math.rint(value) || value < 10 || value > Integer.MAX_VALUE) {
            return QzMinerConfigDefaults.TICK_BUDGET_MS;
        }
        return (int) Math.min(value, QzMinerConfigDefaults.TICK_BUDGET_MAX_MS);
    }

    static int normalizeLegacyCfgTickDuration(int value) {
        return (int) Math.min(Math.max(10, value), QzMinerConfigDefaults.TICK_BUDGET_MAX_MS);
    }

    static File backup(File source, String reason) throws IOException {
        File target = backupTarget(source, reason);
        Files.copy(source.toPath(), target.toPath());
        if (!target.isFile() || target.length() != source.length()) {
            throw new IOException("配置备份不完整: " + source + " -> " + target);
        }
        return target;
    }

    private static File backupTarget(File source, String reason) {
        return new File(source.getParentFile(), source.getName() + "." + System.currentTimeMillis() + "."
                + UUID.randomUUID() + "." + reason + ".bak");
    }

    private static ConfigKey<Double> legacy(String path) {
        return new ConfigKey<Double>(path, LEGACY_NUMBER, null);
    }

    private static ConfigSchema migrationSchema() {
        List<ConfigKey<?>> keys = new ArrayList<ConfigKey<?>>(QzMinerConfigSchema.SCHEMA.keys());
        keys.remove(QzMinerConfigSchema.TICK_BUDGET_MS);
        keys.add(OPTIONAL_TICK_BUDGET);
        keys.add(LEGACY_DURATION);
        keys.add(LEGACY_MAX_BREAK);
        keys.add(LEGACY_SERVER_UNITS);
        keys.add(LEGACY_CLIENT_UNITS);
        return ConfigSchema.of(keys.toArray(new ConfigKey<?>[0]));
    }

    private static boolean isLegacyBudgetPath(String path) {
        return LEGACY_DURATION.name().equals(path) || LEGACY_MAX_BREAK.name().equals(path)
                || LEGACY_SERVER_UNITS.name().equals(path) || LEGACY_CLIENT_UNITS.name().equals(path);
    }
}
