package club.heiqi.qz_miner.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import club.heiqi.config.ConfigException;
import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.runtime.SaveOutcome;
import club.heiqi.config.runtime.ValidationResult;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.schema.FieldType;

/** 服务端命令对 YAML Authority 的受限事务修改入口。 */
public final class ServerConfigMutationService {

    private static final Set<String> WRITABLE_PATHS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "general.greeting",
                    "general.chainRadius",
                    "general.chainMaxBlocks",
                    "general.chainLoggingShellLayers",
                    "general.cableReplaceMaxPerTick",
                    "general.chainWatchdogTimeoutTicks",
                    "general.tickBudgetMs",
                    "general.enableUnlimitedOreFortune",
                    "general.enableFortuneForPlacedOre")));

    private final ConfigManager manager;

    /** @param manager 已启动且由 ConfigBootstrap 持有的 Authority manager */
    public ServerConfigMutationService(ConfigManager manager) {
        if (manager == null) {
            throw new IllegalArgumentException("manager must not be null");
        }
        this.manager = manager;
    }

    /** @return 当前 ConfigBootstrap manager 对应的生产服务 */
    public static ServerConfigMutationService fromBootstrap() {
        ConfigManager manager = ConfigBootstrap.manager();
        if (manager == null) {
            throw new IllegalStateException("ConfigBootstrap.manager() is null");
        }
        return new ServerConfigMutationService(manager);
    }

    /** 列出显式服务端 scalar 白名单，可按完整 path 前缀过滤。 */
    public Result list(String prefix) {
        String filter = prefix == null ? "" : prefix;
        List<String> lines = new ArrayList<String>();
        for (String path : WRITABLE_PATHS) {
            if (path.startsWith(filter)) {
                lines.add(formatValue(path, manager.authority().get(path)));
            }
        }
        return Result.success(lines, null);
    }

    /** 读取白名单字段。 */
    public Result get(String path) {
        String error = validatePath(path);
        if (error != null) {
            return Result.failure(error);
        }
        return Result.success(Collections.singletonList(formatValue(path, manager.authority().get(path))), null);
    }

    /** 严格按 schema FieldType 解析，并经 UILib draft/save 事务提交。 */
    public Result set(String path, String rawValue) {
        String error = validatePath(path);
        if (error != null) {
            return Result.failure(error);
        }
        FieldSpec field = manager.schema().field(path);
        ParseResult parsed = parse(field.type(), rawValue);
        if (!parsed.success) {
            return Result.failure(parsed.error);
        }

        CommittedSnapshot before = ConfigBootstrap.currentCommittedSnapshot();
        DraftBuffer draft = manager.openDraft();
        draft.setDraft(path, parsed.value);
        SaveOutcome outcome = manager.save(draft);
        if (!outcome.isSuccess()) {
            return Result.failure(describe(outcome));
        }
        CommittedSnapshot committed = ConfigBootstrap.currentOrCaptureAfterCommit(manager, before);
        return Result.success(Collections.singletonList(formatValue(path, manager.authority().get(path))), committed);
    }

    /** 从磁盘执行 UILib 三阶段严格 reload；失败时 Authority 与运行态均不推进。 */
    public Result reload() {
        CommittedSnapshot before = ConfigBootstrap.currentCommittedSnapshot();
        try {
            manager.reloadDraftFromDisk();
        } catch (ConfigException e) {
            return Result.failure("reload failed: " + message(e));
        }
        CommittedSnapshot committed = ConfigBootstrap.currentOrCaptureAfterCommit(manager, before);
        return Result.success(Collections.singletonList("config reloaded"), committed);
    }

    private String validatePath(String path) {
        if (!WRITABLE_PATHS.contains(path)) {
            return "path is not writable: " + path;
        }
        FieldSpec field = manager.schema().field(path);
        if (field == null || !isScalar(field.type())) {
            return "path is not a scalar schema field: " + path;
        }
        return null;
    }

    private static boolean isScalar(FieldType type) {
        return type == FieldType.STRING || type == FieldType.NUMBER || type == FieldType.BOOLEAN;
    }

    private static ParseResult parse(FieldType type, String rawValue) {
        if (rawValue == null) {
            return ParseResult.failure("value must not be null");
        }
        switch (type) {
            case STRING:
                return ParseResult.success(rawValue);
            case BOOLEAN:
                if ("true".equals(rawValue)) {
                    return ParseResult.success(Boolean.TRUE);
                }
                if ("false".equals(rawValue)) {
                    return ParseResult.success(Boolean.FALSE);
                }
                return ParseResult.failure("boolean value must be exactly true or false");
            case NUMBER:
                try {
                    double value = Double.parseDouble(rawValue);
                    if (!Double.isFinite(value)) {
                        return ParseResult.failure("number value must be finite");
                    }
                    return ParseResult.success(Double.valueOf(value));
                } catch (NumberFormatException e) {
                    return ParseResult.failure("invalid number: " + rawValue);
                }
            default:
                return ParseResult.failure("unsupported scalar type: " + type);
        }
    }

    private static String describe(SaveOutcome outcome) {
        if (outcome.validation() != null) {
            ValidationResult validation = outcome.validation();
            return "save " + outcome.status() + ": " + validation.errors();
        }
        return "save " + outcome.status() + ": " + outcome.errorMessage();
    }

    private static String formatValue(String path, Object value) {
        return path + " = " + String.valueOf(value);
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isEmpty() ? error.getClass().getSimpleName() : value;
    }

    private static final class ParseResult {
        private final boolean success;
        private final Object value;
        private final String error;

        private ParseResult(boolean success, Object value, String error) {
            this.success = success;
            this.value = value;
            this.error = error;
        }

        private static ParseResult success(Object value) {
            return new ParseResult(true, value, null);
        }

        private static ParseResult failure(String error) {
            return new ParseResult(false, null, error);
        }
    }

    /** 命令层可直接呈现的不可变操作结果。 */
    public static final class Result {
        private final boolean success;
        private final List<String> lines;
        private final CommittedSnapshot committed;

        private Result(boolean success, List<String> lines, CommittedSnapshot committed) {
            this.success = success;
            this.lines = Collections.unmodifiableList(new ArrayList<String>(lines));
            this.committed = committed;
        }

        /** @return 成功时 true */
        public boolean isSuccess() {
            return success;
        }

        /** @return 面向命令发送者的输出行 */
        public List<String> lines() {
            return lines;
        }

        /** @return 成功 set/reload 的提交令牌；只读操作或失败时为 null */
        public CommittedSnapshot committed() {
            return committed;
        }

        private static Result success(List<String> lines, CommittedSnapshot committed) {
            return new Result(true, lines, committed);
        }

        private static Result failure(String message) {
            return new Result(false, Collections.singletonList(message), null);
        }

        /** 命令语法失败工厂，避免命令层伪造提交结果。 */
        public static Result commandFailure(String message) {
            return failure(message);
        }
    }
}
