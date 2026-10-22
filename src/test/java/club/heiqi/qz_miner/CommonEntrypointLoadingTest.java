package club.heiqi.qz_miner;

import java.net.URL;
import java.net.URLClassLoader;
import org.junit.Assert;
import org.junit.Test;
import cpw.mods.fml.common.Mod;

/** 拒绝所有客户端类的类加载器验证公共入口；不替代 dedicated server 游戏启动。 */
public class CommonEntrypointLoadingTest {
    @Test public void configurationFactoryDeclarationDoesNotLinkClientClasses() throws Exception {
        URL main = MyMod.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader isolated = new URLClassLoader(new URL[] {main}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.client.") || name.startsWith("club.heiqi.qz_miner.client.")
                        || name.equals("club.heiqi.qz_miner.ClientProxy")
                        || name.startsWith("club.heiqi.uilib.ui.host.minecraft.")) throw new ClassNotFoundException(name);
                if (name.startsWith("club.heiqi.qz_miner.")) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            for (String name : new String[] {"club.heiqi.qz_miner.MyMod", "club.heiqi.qz_miner.CommonProxy",
                    "club.heiqi.qz_miner.config.ConfigBootstrap", "club.heiqi.qz_miner.config.QzMinerConfigSchema"}) {
                Class<?> type = Class.forName(name, false, isolated);
                type.getDeclaredMethods(); type.getDeclaredFields();
            }
            Class<?> mod = Class.forName("club.heiqi.qz_miner.MyMod", false, isolated);
            Assert.assertEquals("club.heiqi.qz_miner.client.settings.MinerConfigGuiFactory", mod.getAnnotation(Mod.class).guiFactory());
        }
    }
}
