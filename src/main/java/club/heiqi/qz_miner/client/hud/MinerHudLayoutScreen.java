package club.heiqi.qz_miner.client.hud;

import club.heiqi.qz_miner.client.ClientConnectionLifecycle;
import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.qz_miner.client.settings.MinerSettingsSave;
import club.heiqi.qz_miner.client.settings.MinerSettingsScreens;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.uilib.ui.host.minecraft.MinecraftHudEditorScreen;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.reactive.Signal;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

/** Minecraft 只提供 GUI Scale 和生命周期；布局业务直接组合正式 final 编辑宿主。 */
public final class MinerHudLayoutScreen {
    private MinerHudLayoutScreen() { }
    public static MinecraftHudEditorScreen create(GuiScreen parent) {
        ReactiveDomain domain = new ReactiveDomain(MinerSettingsScreens.CLIENT);
        MinecraftHudEditorScreen[] created = new MinecraftHudEditorScreen[1];
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.capture();
        domain.execute(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            Signal<Float> guiScale = domain.signal((float) new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor());
            MinerSettingsSave saver = new MinerSettingsSave(MinerSettingsScreens.IO,
                    task -> MinerSettingsScreens.CLIENT.execute(() -> domain.execute(task)),
                    () -> ClientConnectionLifecycle.capture() == token);
            MinerHudModel model = new MinerHudModel(domain, ClientI18n::tr);
            sample(model);
            MinerHudLayoutPage page = new MinerHudLayoutPage(domain, ConfigBootstrap.document(), saver, guiScale,
                    () -> ClientConnectionLifecycle.capture() == token, ClientI18n::tr,
                    () -> MinerSettingsScreens.open(parent), model);
            page.locale(mc.gameSettings.language);
            created[0] = new MinecraftHudEditorScreen(domain, context -> {
                context.own(new ScaleTicker(domain, guiScale, model, token, page)); page.build(context);
            });
        });
        return created[0];
    }

    private static void sample(MinerHudModel model) {
        Minecraft mc = Minecraft.getMinecraft();
        model.context(club.heiqi.qz_miner.MyMod.chainStateService == null ? null
                : club.heiqi.qz_miner.MyMod.chainStateService.getClientState(),
                club.heiqi.qz_miner.client.settings.ClientServerConfigFacts.confirmed(), mc.gameSettings.language);
        club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationProjection projection =
                club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationProjection.installed();
        model.header(projection == null ? null : projection.currentHeader());
    }

    /** 页面关闭时解除真实 GUI Scale 和既有固定标量事实订阅；不注册输入监听。 */
    public static final class ScaleTicker implements AutoCloseable {
        private final ReactiveDomain domain;
        private final Signal<Float> guiScale;
        private boolean closed;
        private final MinerHudModel model;
        private final ClientConnectionLifecycle.Token token;
        private final MinerHudLayoutPage page;
        private ScaleTicker(ReactiveDomain domain, Signal<Float> guiScale, MinerHudModel model, ClientConnectionLifecycle.Token token, MinerHudLayoutPage page) {
            this.domain = domain; this.guiScale = guiScale; this.model = model; this.token = token; this.page = page;
            FMLCommonHandler.instance().bus().register(this);
        }
        @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
            if (closed || event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getMinecraft();
            domain.execute(() -> {
                if (closed || ClientConnectionLifecycle.capture() != token) return;
                guiScale.set((float) new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor());
                sample(model);
                page.locale(mc.gameSettings.language);
            });
        }
        @Override public void close() {
            if (closed) return; closed = true; FMLCommonHandler.instance().bus().unregister(this);
        }
    }
}
