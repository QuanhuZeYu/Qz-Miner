package club.heiqi.qz_miner.config;

import java.io.IOException;
import java.util.ArrayList;

import club.heiqi.uilib.ui.config.ConfigCodecs;
import club.heiqi.uilib.ui.config.ConfigKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


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
                    "general.harvestExhaustionPerBlock",
                    "general.enableUnlimitedOreFortune",
                    "general.enableFortuneForPlacedOre")));

    private ServerConfigMutationService() { }

    public static ServerConfigMutationService fromBootstrap() {
        if (ConfigBootstrap.document() == null) throw new IllegalStateException("配置尚未启动");
        return new ServerConfigMutationService();
    }

    /** 列出显式服务端 scalar 白名单，可按完整 path 前缀过滤。 */
    public Result list(String prefix) {
        String filter = prefix == null ? "" : prefix;
        List<String> lines = new ArrayList<String>();
        for (String path : WRITABLE_PATHS) {
            if (path.startsWith(filter)) {
                lines.add(formatValue(path, ConfigBootstrap.document().get(QzMinerConfigSchema.key(path))));
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
        return Result.success(Collections.singletonList(formatValue(path, ConfigBootstrap.document().get(QzMinerConfigSchema.key(path)))), null);
    }

    /** 仅显式 general scalar 白名单，完整候选校验和落盘由业务事务入口负责。 */
    public Result set(String path, String rawValue) {
        String error = validatePath(path);
        if (error != null) return Result.failure(error);
        ConfigKey<?> key = QzMinerConfigSchema.key(path);
        ParseResult parsed = parse(key, rawValue);
        if (!parsed.success) return Result.failure(parsed.error);
        try {
            CommittedSnapshot committed = ConfigBootstrap.commit(Collections.singletonMap(key, parsed.value));
            return Result.success(Collections.singletonList(formatValue(path, ConfigBootstrap.document().get(key))), committed);
        } catch (IOException | RuntimeException failure) {
            return Result.failure("save failed: " + message(failure));
        }
    }

    public Result reload() {
        try {
            CommittedSnapshot committed = ConfigBootstrap.reload();
            return Result.success(Collections.singletonList("config reloaded"), committed);
        } catch (IOException | RuntimeException failure) {
            return Result.failure("reload failed: " + message(failure));
        }
    }

    private static String validatePath(String path) {
        return WRITABLE_PATHS.contains(path) ? null : "path is not writable: " + path;
    }

    private static ParseResult parse(ConfigKey<?> key, String rawValue) {
        if (rawValue == null) return ParseResult.failure("value must not be null");
        if (key.codec() == ConfigCodecs.STRING) return ParseResult.success(rawValue);
        if (key.codec() == ConfigCodecs.BOOLEAN) {
            if ("true".equals(rawValue)) return ParseResult.success(Boolean.TRUE);
            if ("false".equals(rawValue)) return ParseResult.success(Boolean.FALSE);
            return ParseResult.failure("boolean value must be exactly true or false");
        }
        if (key.codec() == ConfigCodecs.DOUBLE) {
            try {
                double value = Double.parseDouble(rawValue);
                return Double.isFinite(value) ? ParseResult.success(value)
                        : ParseResult.failure("number value must be finite");
            } catch (NumberFormatException failure) {
                return ParseResult.failure("invalid number: " + rawValue);
            }
        }
        return ParseResult.failure("unsupported scalar type: " + key.name());
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

        /** 为命令语法错误创建失败结果，提交令牌保持为空。 */
        public static Result commandFailure(String message) {
            return failure(message);
        }
    }
}
