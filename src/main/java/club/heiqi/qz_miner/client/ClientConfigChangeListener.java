package club.heiqi.qz_miner.client;

import java.util.Map;

import club.heiqi.config.ConfigChangeEvent;
import club.heiqi.config.ConfigChangeListener;
import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.runtime.SaveOutcome;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigSemanticValidator;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ParseOutcome;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.qz_miner.config.ConfigValueBridge;
import club.heiqi.qz_miner.network.PacketChainConfigRequest;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * 客户端配置变更：订阅 UILib {@code BATCH_SAVE}，分侧发布运行字段并网络同步。
 *
 * <p>持有注册时的 final {@link ConfigManager}，按 manager 实例保证一次订阅（不用与 manager 脱节的全局 boolean）。
 * BATCH_SAVE 可能同步触发：在回调线程立即抓取不可变 {@link ValidatedSnapshot}，再经
 * {@link ClientMainThreadDispatcher} 异步发布 client 字段；general 仅在集成服运行时经
 * {@link ServerMainThreadDispatcher} 写服务端主线程。远程多人客户端不写 general static 充当服务端权威。</p>
 *
 * <p>UILib 4.5.1 DraftBuffer 无法表达 finite/整数/交叉约束：若 Authority 在 save 后仍语义非法，
 * 用 last-valid 恢复 Authority+YAML（openDraft→setDraft 全字段→save），重入保护避免死循环。</p>
 */
@SideOnly(Side.CLIENT)
public class ClientConfigChangeListener implements ConfigChangeListener {

    private static volatile ConfigManager subscribedManager;

    private final ConfigManager manager;
    private volatile boolean restoring;

    /**
     * @param manager 注册时的 ConfigManager（final 持有）
     */
    public ClientConfigChangeListener(ConfigManager manager) {
        if (manager == null) {
            throw new IllegalArgumentException("manager must not be null");
        }
        this.manager = manager;
    }

    /**
     * 无参构造：使用 {@link ConfigBootstrap#manager()}（生产路径）。
     */
    public ClientConfigChangeListener() {
        this(requireBootstrapManager());
    }

    private static ConfigManager requireBootstrapManager() {
        ConfigManager m = ConfigBootstrap.manager();
        if (m == null) {
            throw new IllegalStateException("ConfigBootstrap.manager() is null; preInit must run first");
        }
        return m;
    }

    /**
     * 按 manager 实例幂等订阅。
     */
    public void register() {
        if (subscribedManager == manager) {
            return;
        }
        if (subscribedManager != null && subscribedManager != manager) {
            MyMod.LOG.warn("Replacing BATCH_SAVE subscription for new ConfigManager instance");
            try {
                subscribedManager.eventBus().unsubscribe(this);
            } catch (RuntimeException ignored) {
                // 旧 listener 可能是另一实例；新实例仍 subscribe
            }
        }
        manager.eventBus().subscribe(this);
        subscribedManager = manager;
        MyMod.LOG.info("Subscribed Config BATCH_SAVE listener for manager instance");
    }

    /**
     * 测试钩子。
     */
    public static void resetSubscriptionForTests() {
        subscribedManager = null;
    }

    @Override
    public void onConfigChanged(ConfigChangeEvent event) {
        if (event == null || event.getType() != ConfigChangeEvent.ChangeType.BATCH_SAVE) {
            return;
        }
        if (restoring) {
            return;
        }

        // 同步路径立即校验并抓取不可变快照，避免后续 save 覆盖后再异步读到新值
        ParseOutcome outcome = ConfigSemanticValidator.parseAndValidate(manager.authority());
        if (!outcome.isValid()) {
            MyMod.LOG.error("BATCH_SAVE authority failed semantic validation; restoring last-valid: {}",
                    outcome.result.summary());
            restoreLastValidOrFail();
            return;
        }

        final ValidatedSnapshot snapshot = outcome.snapshot;
        ClientMainThreadDispatcher.run(new Runnable() {
            @Override
            public void run() {
                publishClientAndRequest(snapshot);
            }
        });

        if (isIntegratedServerRunning()) {
            ServerMainThreadDispatcher.run(new Runnable() {
                @Override
                public void run() {
                    ConfigValueBridge.applyGeneralFromSnapshot(snapshot);
                    ConfigBootstrap.updateLastValidSnapshot(snapshot);
                    MyMod.LOG.debug("Applied general config on server main thread after BATCH_SAVE");
                }
            });
        } else {
            // 主菜单 / 远程客户端：不写 general static；仅更新 last-valid 供后续 serverStarting 读取 Authority
            ConfigBootstrap.updateLastValidSnapshot(snapshot);
        }
    }

    private void publishClientAndRequest(ValidatedSnapshot snapshot) {
        ConfigValueBridge.applyClientFromSnapshot(snapshot);
        // radius/maxBlocks 请求值直接取严格校验后的快照（不是 Config.general 静态，远程客户端 general 可能未写）
        syncClientRequestedChainConfig(snapshot.chainRadius, snapshot.chainMaxBlocks);
        ConfigBootstrap.updateLastValidSnapshot(snapshot);
    }

    /**
     * 将客户端请求的连锁配置同步到本地状态与服务端。
     *
     * @param requestedRadius    已校验半径
     * @param requestedMaxBlocks 已校验上限
     */
    public static void syncClientRequestedChainConfig(int requestedRadius, int requestedMaxBlocks) {
        if (MyMod.chainStateService == null) {
            return;
        }

        MyMod.chainStateService.setClientRequestedChainConfig(requestedRadius, requestedMaxBlocks);
        if (MyMod.networkMain == null || FMLClientHandler.instance().getClient().isSingleplayer()) {
            return;
        }

        MyMod.networkMain.network.sendToServer(new PacketChainConfigRequest(requestedRadius, requestedMaxBlocks));
    }

    /**
     * 兼容旧调用：从 last-valid 或当前 Config 静态取请求值（登录等路径）。
     */
    public static void syncClientRequestedChainConfig() {
        ValidatedSnapshot snap = ConfigBootstrap.lastValidSnapshot();
        if (snap != null) {
            syncClientRequestedChainConfig(snap.chainRadius, snap.chainMaxBlocks);
            return;
        }
        syncClientRequestedChainConfig(
                club.heiqi.qz_miner.Config.chainRadius,
                club.heiqi.qz_miner.Config.chainMaxBlocks);
    }

    private void restoreLastValidOrFail() {
        ValidatedSnapshot last = ConfigBootstrap.lastValidSnapshot();
        if (last == null) {
            throw new IllegalStateException("BATCH_SAVE invalid and no last-valid snapshot to restore");
        }
        restoring = true;
        try {
            DraftBuffer draft = manager.openDraft();
            for (Map.Entry<String, Object> e : last.typedByPath.entrySet()) {
                draft.setDraft(e.getKey(), e.getValue());
            }
            SaveOutcome outcome = manager.save(draft);
            if (!outcome.isSuccess()) {
                throw new IllegalStateException("Failed to restore last-valid YAML after invalid BATCH_SAVE: "
                        + outcome.status() + " " + outcome.errorMessage());
            }
            // save 会再触发 BATCH_SAVE；restoring=true 时忽略，手动发布 client
            ConfigValueBridge.applyClientFromSnapshot(last);
            MyMod.LOG.warn("Restored last-valid config after illegal BATCH_SAVE values");
        } finally {
            restoring = false;
        }
    }

    private static boolean isIntegratedServerRunning() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || !mc.isSingleplayer()) {
                return false;
            }
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            return server != null;
        } catch (RuntimeException e) {
            return false;
        } catch (LinkageError e) {
            return false;
        }
    }
}
