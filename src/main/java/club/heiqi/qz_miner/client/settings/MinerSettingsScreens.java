package club.heiqi.qz_miner.client.settings;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import club.heiqi.qz_miner.client.ClientConnectionLifecycle;
import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.qz_miner.client.ClientMainThreadDispatcher;
import club.heiqi.qz_miner.client.hud.MinerHudLayoutScreen;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/** 设置工作台装配；有界后台队列只执行本机文件事务。 */
public final class MinerSettingsScreens {
    public static final Executor IO = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(4), task -> {
                Thread thread = new Thread(task, "qz-miner-config-io"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    public static final Executor CLIENT = task -> {
        if (!ClientMainThreadDispatcher.tryRun(task)) throw new java.util.concurrent.RejectedExecutionException("客户端已关闭");
    };
    private MinerSettingsScreens() { }
    public static void open(GuiScreen parent) { Minecraft.getMinecraft().displayGuiScreen(new MinerSettingsScreen(parent)); }

    static Session session(GuiScreen parent) {
        Minecraft mc = Minecraft.getMinecraft();
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.capture();
        MinerSettingsAccess access = access();
        BooleanSupplier lifecycle = () -> ClientConnectionLifecycle.capture() == token;
        BooleanSupplier current = () -> lifecycle.getAsBoolean() && access() == access;
        ReactiveDomain domain = new ReactiveDomain(CLIENT);
        Session[] created = new Session[1];
        domain.execute(() -> {
        MinerSettingsDraft draft = new MinerSettingsDraft(ConfigBootstrap.document(), access);
        MinerSettingsSave save = new MinerSettingsSave(IO, task -> CLIENT.execute(() -> domain.execute(task)), lifecycle, current);
        MinerSettingsPage page = new MinerSettingsPage(domain, draft, save, ClientI18n::tr, key -> {
            String value = ClientServerConfigFacts.value(key);
            return value.isEmpty() ? ClientI18n.tr("settings.qz_miner.unknown")
                    : ClientI18n.tr("settings.qz_miner.confirmed") + " " + value;
        }, () -> mc.displayGuiScreen(parent), () -> open(parent),
                () -> mc.displayGuiScreen(MinerHudLayoutScreen.create(parent)));
        created[0] = new Session(domain, page, current);
        });
        return created[0];
    }

    public static MinerSettingsAccess access() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null && !ClientConnectionLifecycle.capture().isConnectionActive()) return MinerSettingsAccess.MENU;
        if (!mc.isSingleplayer() || mc.getIntegratedServer() == null || mc.thePlayer == null) return MinerSettingsAccess.REMOTE;
        net.minecraft.entity.player.EntityPlayerMP authority = mc.getIntegratedServer().getConfigurationManager()
                .func_152612_a(mc.thePlayer.getCommandSenderName());
        boolean admin = authority != null && authority.canCommandSenderUseCommand(4, "qzminer");
        return admin ? MinerSettingsAccess.INTEGRATED_ADMIN : MinerSettingsAccess.INTEGRATED_READ_ONLY;
    }

    static final class Session {
        final ReactiveDomain domain;
        final MinerSettingsPage page;
        final BooleanSupplier current;
        Session(ReactiveDomain domain, MinerSettingsPage page, BooleanSupplier current) {
            this.domain = domain; this.page = page; this.current = current;
        }
    }
}
