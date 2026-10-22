package club.heiqi.qz_miner.client.settings;

import java.util.Set;
import cpw.mods.fml.client.IModGuiFactory;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/** FML 只在客户端按字符串加载此入口，菜单与游戏中使用同一个 Scene 页面。 */
@SideOnly(Side.CLIENT)
public final class MinerConfigGuiFactory implements IModGuiFactory {
    @Override public void initialize(Minecraft minecraft) { }
    @Override public Class<? extends GuiScreen> mainConfigGuiClass() { return MinerSettingsScreen.class; }
    @Override public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() { return null; }
    @Override public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) { return null; }
}
