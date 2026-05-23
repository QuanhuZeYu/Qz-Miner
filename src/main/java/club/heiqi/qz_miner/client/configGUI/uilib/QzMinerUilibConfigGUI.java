package club.heiqi.qz_miner.client.configGUI.uilib;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.client.ClientConfigChangeListener;
import club.heiqi.uilib.config.ForgeConfigTemplateScreen;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.Configuration;

/**
 * UILib 配置模板界面。
 *
 * <p>该类只由 UILib 适配器在依赖存在时加载，避免主配置入口直接依赖 UILib 类型。</p>
 */
public class QzMinerUilibConfigGUI extends ForgeConfigTemplateScreen {

    /**
     * 创建 UILib 配置界面。
     *
     * @param parentScreen 父界面
     */
    public QzMinerUilibConfigGUI(GuiScreen parentScreen) {
        super(parentScreen, createSpec());
    }

    /**
     * 构建 Qz-UILib 配置模板规格。
     *
     * @return 配置模板规格
     */
    private static Spec createSpec() {
        return new Spec(MyMod.MODID, MyMod.MOD_NAME + " 配置", Config.config)
                .setSubtitle("Qz-UILib Config")
                .setDescription("使用 Qz-UILib 的 HTML-like 配置模板替换默认 Forge 配置界面。")
                .setConfigPath(Config.getConfigPath())
                .setSaveHandler(new SaveHandler() {

                    @Override
                    public void onSave(Configuration configuration) {
                        ClientConfigChangeListener.reloadAndSyncAfterConfigSaved();
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
