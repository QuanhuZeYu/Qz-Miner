package club.heiqi.qz_miner.client.configGUI;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.client.ClientConfigChangeListener;
import club.heiqi.uilib.config.ForgeConfigTemplateScreen;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.Configuration;

/**
 * 使用 QzUILib 配置模板替换默认 Forge 配置页。
 */
public class QzMinerConfigGUI extends ForgeConfigTemplateScreen {

    /**
     * 创建模组配置界面。
     *
     * @param parentScreen 父界面
     */
    public QzMinerConfigGUI(GuiScreen parentScreen) {
        super(parentScreen, createSpec());
    }

    /**
     * 构建 QzUILib 配置模板规格。
     *
     * @return 配置模板规格
     */
    private static Spec createSpec() {
        return new Spec(MyMod.MODID, MyMod.MOD_NAME + " 配置", Config.config)
                .setSubtitle("QzUILib Config")
                .setDescription("使用 QzUILib 的 HTML-like 配置模板替换默认 Forge 配置界面。")
                .setConfigPath(Config.getConfigPath())
                .setSaveHandler(new SaveHandler() {

                    @Override
                    public void onSave(net.minecraftforge.common.config.Configuration configuration) {
                        Config.saveAndReload();
                        ClientConfigChangeListener.syncClientRequestedChainConfig();
                    }
                })
                .addCategory(new CategorySpec(Configuration.CATEGORY_GENERAL)
                        .setTitle("General")
                        .setDescription("服务端与通用连锁行为配置。"))
                .addCategory(new CategorySpec(Config.CATEGORY_CLIENT)
                        .setTitle("Client")
                        .setDescription("客户端预览渲染与本地显示配置。"));
    }
}
