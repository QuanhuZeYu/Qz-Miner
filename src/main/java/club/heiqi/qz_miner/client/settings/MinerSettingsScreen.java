package club.heiqi.qz_miner.client.settings;

import club.heiqi.uilib.ui.host.minecraft.MinecraftSceneScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/** 屏幕只跟随真实宿主语言与生命周期，输入、绘制和编辑均归正式 Scene 宿主。 */
public final class MinerSettingsScreen extends MinecraftSceneScreen {
    private final MinerSettingsScreens.Session session;
    private boolean knownFacts;
    private int knownRadius = -1;
    private int knownBlocks = -1;
    private String knownLanguage;
    public MinerSettingsScreen(GuiScreen parent) { this(MinerSettingsScreens.session(parent)); }
    private MinerSettingsScreen(MinerSettingsScreens.Session session) {
        super(session.domain, session.page); this.session = session;
    }
    @Override public void updateScreen() {
        super.updateScreen();
        Minecraft mc = Minecraft.getMinecraft();
        session.domain.execute(() -> {
            String language = mc.gameSettings.language;
            session.page.locale(language);
            session.page.active(session.current.getAsBoolean());
            boolean facts = ClientServerConfigFacts.confirmed();
            club.heiqi.qz_miner.chain.state.ChainClientState state = club.heiqi.qz_miner.MyMod.chainStateService == null
                    ? null : club.heiqi.qz_miner.MyMod.chainStateService.getClientState();
            int radius = state == null ? -1 : state.getServerChainRadius();
            int blocks = state == null ? -1 : state.getServerChainMaxBlocks();
            if (facts != knownFacts || radius != knownRadius || blocks != knownBlocks
                    || !java.util.Objects.equals(language, knownLanguage)) {
                knownFacts = facts; knownRadius = radius; knownBlocks = blocks; knownLanguage = language;
                session.page.refreshServerFacts();
            }
        });
    }
}
