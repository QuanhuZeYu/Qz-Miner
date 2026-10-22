package club.heiqi.qz_miner.client.settings;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import club.heiqi.qz_miner.config.ConfigSemanticValidator;
import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigKey;

/** 保留原始输入的页面草稿；只有全量校验成功的不可变候选可以进入原提交入口。 */
public final class MinerSettingsDraft {
    private final ConfigDocument baseline;
    private final MinerSettingsAccess access;
    private final Map<ConfigKey<?>, Object> edits = new LinkedHashMap<ConfigKey<?>, Object>();

    public MinerSettingsDraft(ConfigDocument baseline, MinerSettingsAccess access) {
        this.baseline = Objects.requireNonNull(baseline, "baseline");
        this.access = Objects.requireNonNull(access, "access");
    }

    public ConfigDocument baseline() { return baseline; }
    public MinerSettingsAccess access() { return access; }
    public Object value(ConfigKey<?> key) { return edits.containsKey(key) ? edits.get(key) : baseline.get(key); }
    public boolean dirty() { return !edits.isEmpty(); }

    public void set(ConfigKey<?> key, Object rawValue) {
        if (!access.canEdit(key)) throw new IllegalStateException("此配置由服务器管理，只读: " + key.name());
        Object comparable = rawValue;
        if (key.defaultValue() instanceof Double && rawValue instanceof String) {
            try { comparable = Double.valueOf((String) rawValue); }
            catch (NumberFormatException ignored) { /* 非法输入仍保留在草稿。 */ }
        }
        if (Objects.equals(baseline.get(key), comparable)) edits.remove(key);
        else edits.put(key, rawValue);
    }

    public Validation validate() {
        Map<String, String> errors = new LinkedHashMap<String, String>();
        Map<ConfigKey<?>, Object> typed = new LinkedHashMap<ConfigKey<?>, Object>();
        for (Map.Entry<ConfigKey<?>, Object> edit : edits.entrySet()) {
            ConfigKey<?> key = edit.getKey();
            try {
                if (!access.canEdit(key)) throw new IllegalStateException("服务器管理字段只读");
                Object raw = edit.getValue();
                Object value = key.defaultValue() instanceof Double && raw instanceof String
                        ? Double.valueOf((String) raw) : raw;
                // withAll 使用正式 codec、范围和不可变快照；不复制类型/范围校验规则。
                ConfigDocument checked = baseline.withAll(Collections.singletonMap(key, value));
                typed.put(key, checked.get(key));
            } catch (IllegalArgumentException | IllegalStateException failure) {
                errors.put(key.name(), failure.getMessage());
            }
        }
        ConfigDocument candidate = null;
        if (errors.isEmpty()) {
            candidate = baseline.withAll(typed);
            ConfigSemanticValidator.ParseOutcome outcome = ConfigSemanticValidator.validate(candidate);
            errors.putAll(outcome.result.errors());
        }
        return new Validation(errors.isEmpty() ? candidate : null, typed, errors);
    }

    public static final class Validation {
        public final ConfigDocument candidate;
        public final Map<ConfigKey<?>, Object> updates;
        public final Map<String, String> errors;

        Validation(ConfigDocument candidate, Map<ConfigKey<?>, Object> updates, Map<String, String> errors) {
            this.candidate = candidate;
            this.updates = Collections.unmodifiableMap(new LinkedHashMap<ConfigKey<?>, Object>(updates));
            this.errors = Collections.unmodifiableMap(new LinkedHashMap<String, String>(errors));
        }
        public boolean valid() { return candidate != null && errors.isEmpty(); }
    }
}
