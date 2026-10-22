package club.heiqi.qz_miner.config;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collection;

import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigKey;

/** 测试自己隔离静态宿主；生产代码不提供注入器、重置器或替代存储。 */
public final class ConfigTestSupport {
    private ConfigTestSupport() { }

    public static void reset() {
        try {
            Field state = ConfigBootstrap.class.getDeclaredField("state");
            state.setAccessible(true);
            state.set(null, null);
            Field listeners = ConfigBootstrap.class.getDeclaredField("LISTENERS");
            listeners.setAccessible(true);
            ((Collection<?>) listeners.get(null)).clear();
            ConfigValueBridge.applyAll(ConfigSemanticValidator.validate(
                    ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA)).snapshot);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    public static Map<ConfigKey<?>, Object> changes(Object... pairs) {
        Map<ConfigKey<?>, Object> result = new LinkedHashMap<ConfigKey<?>, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(QzMinerConfigSchema.key((String) pairs[i]), pairs[i + 1]);
        }
        return result;
    }
}
