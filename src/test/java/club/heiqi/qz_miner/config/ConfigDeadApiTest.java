package club.heiqi.qz_miner.config;

import java.lang.reflect.Method;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.Config;

/** 防止绕过分侧快照发布的旧 API 回归。 */
public class ConfigDeadApiTest {

    @Test
    public void authorityReloadAndSnapshotSetterApisRemainDeleted() {
        assertNoMethod(Config.class, "load");
        assertNoMethod(Config.class, "reloadFromAuthority");
        assertNoMethod(ConfigValueBridge.class, "applyFromAuthority");
        assertNoMethod(ConfigBootstrap.class, "updateLastValidSnapshot");
        assertNoMethod(ConfigBootstrap.class, "updateCurrentValidatedSnapshot");
    }

    @Test(expected = IllegalStateException.class)
    public void currentSnapshotGetterFailsFastBeforeBootstrap() {
        ConfigBootstrap.resetForTests();
        ConfigBootstrap.currentValidatedSnapshot();
    }

    private static void assertNoMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            Assert.assertFalse(type.getName() + " must not declare " + name, method.getName().equals(name));
        }
    }
}
