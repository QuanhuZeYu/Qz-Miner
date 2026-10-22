package club.heiqi.qz_miner.client.hud;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationProjection;
import club.heiqi.qz_miner.client.ClientConnectionLifecycle;
import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.qz_miner.client.settings.ClientServerConfigFacts;
import club.heiqi.qz_miner.client.settings.MinerSettingsScreens;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.host.minecraft.MinecraftSceneHud;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.hud.SceneHud;
import club.heiqi.uilib.ui.scene.hud.SceneHudPlacement;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

/** 每个世界持有一个只读 HUD 宿主；普通 HUD 完全不接管输入。 */
public final class MinerHudController {
    private MinecraftSceneHud hud;
    private ChainPreviewPresentationProjection.Subscription subscription;
    private ReactiveDomain domain;
    private MinerHudModel model;
    private Signal<SceneHudPlacement> placement;
    private Signal<Float> guiScale;
    private ConfigDocument document;
    private ClientConnectionLifecycle.Token lifecycle;

    public void register() { FMLCommonHandler.instance().bus().register(this); }

    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.capture();
        if (mc.theWorld == null || mc.thePlayer == null || !token.isWorldActive()) { clear(); return; }
        if (lifecycle != token) { clear(); install(token); }
        ReactiveDomain currentDomain = domain;
        currentDomain.execute(() -> {
            if (lifecycle != token) return;
            ConfigDocument next = ConfigBootstrap.document();
            if (document != next) { document = next; placement.set(MinerHudPlacement.read(next)); }
            guiScale.set((float) new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor());
            model.context(MyMod.chainStateService == null ? null : MyMod.chainStateService.getClientState(),
                    ClientServerConfigFacts.confirmed(), mc.gameSettings.language);
        });
    }

    private void install(ClientConnectionLifecycle.Token token) {
        lifecycle = token; domain = new ReactiveDomain(MinerSettingsScreens.CLIENT);
        ReactiveDomain currentDomain = domain;
        currentDomain.execute(() -> {
        if (lifecycle != token) return;
        model = new MinerHudModel(domain, ClientI18n::tr);
        placement = domain.signal(MinerHudPlacement.read(ConfigBootstrap.document()));
        guiScale = domain.signal(1f);
        hud = new MinecraftSceneHud(domain, context -> context.mountOwned(
                SceneHud.create(context, model.content(context), placement, guiScale)), () -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    return mc.theWorld != null && mc.thePlayer != null && mc.currentScreen == null
                            && ConfigBootstrap.document().get(QzMinerConfigSchema.HUD_ENABLED);
                }).register();
        ChainPreviewPresentationProjection projection = ChainPreviewPresentationProjection.installed();
        if (projection != null) {
            subscription = projection.subscribe(header -> currentDomain.execute(() -> {
                if (lifecycle == token) model.header(header);
            }));
            model.header(projection.currentHeader());
        }
        });
    }

    public void clear() {
        ChainPreviewPresentationProjection.Subscription oldSubscription = subscription;
        MinecraftSceneHud oldHud = hud;
        subscription = null; hud = null;
        lifecycle = null; document = null; model = null; domain = null;
        try { if (oldSubscription != null) oldSubscription.unsubscribe(); }
        finally { if (oldHud != null) oldHud.close(); }
    }
}
