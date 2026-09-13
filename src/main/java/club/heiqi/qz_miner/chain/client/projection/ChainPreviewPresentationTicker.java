package club.heiqi.qz_miner.chain.client.projection;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.ChainPreviewVisualSettings;
import club.heiqi.qz_miner.config.CommittedSnapshot;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

/**
 * B1.1 表现投影的装配采样器：客户端 {@link TickEvent.ClientTickEvent#END} 采样一次并发布
 * {@link ChainPreviewPresentationProjection} 的 O(1) header。
 *
 * <p>数据源全部是 O(1) 只读访问器：链预览状态 / 控制器远端在途状态 / 客户端阶段投影 /
 * 世界身份 / 生命周期 epoch / 配置 epoch / 对象组 revision / 截断开关位。采样在主线程 tick，
 * 不触碰渲染帧路径。</p>
 *
 * <h3>失效维度来源</h3>
 * <ul>
 *   <li>world：{@code Minecraft.theWorld} 的身份哈希 + 维度 id（换世界/换维度即变）</li>
 *   <li>lifecycle：{@link #bumpLifecycleEpoch()}（清理入口调用）</li>
 *   <li>configRevision：{@link ConfigBootstrap#currentCommittedSnapshot()} 的 epoch；无提交时为 0（未接线）</li>
 *   <li>objectGroupRevision：{@code ChainClientState.getServerObjectGroupRevision()}</li>
 *   <li>serverRoundId：客户端当前无来源，恒 0（未接线；服务端代际失效由 phase 投影的 generation 覆盖）</li>
 *   <li>generation：phase 投影 generation + 预览代 generation</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public final class ChainPreviewPresentationTicker {

    /** 生命周期 epoch：清理入口自增，用于 header 失效与订阅者重置。 */
    private static volatile long lifecycleEpoch;

    private final ChainPreviewPresentationProjection projection;

    /**
     * @param projection 待采样的投影实例
     */
    public ChainPreviewPresentationTicker(ChainPreviewPresentationProjection projection) {
        this.projection = projection;
    }

    /** 注册到 FML 客户端事件总线（客户端 init 调用一次）。 */
    public void register() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /**
     * 客户端 tick 采样（仅 END 阶段）。
     *
     * @param event 客户端 tick 事件
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        sample();
    }

    /**
     * 采样并发布一次（O(1)；客户端主线程）。
     *
     * @return 发布后的当前 header；投影为空时为 null
     */
    public ChainPreviewPresentationHeader sample() {
        if (projection == null) {
            return null;
        }
        if (ClientProxy.chainPreviewController == null) {
            return projection.currentHeader();
        }
        return projection.sampleAndPublish(
            ClientProxy.chainPreviewController.getPreviewState(),
            ClientProxy.chainPreviewController,
            ClientProxy.clientPhaseProjection,
            worldIdentity(),
            lifecycleEpoch,
            serverRoundId(),
            configRevision(),
            objectGroupRevision(),
            ChainPreviewVisualSettings.current().isTruncationSignalEnabled());
    }

    /** 生命周期清理（断线/世界卸载/连接接管）：epoch 自增，使订阅者与 header 失效。 */
    public static void bumpLifecycleEpoch() {
        lifecycleEpoch++;
    }

    /** @return 当前生命周期 epoch */
    public static long getLifecycleEpoch() {
        return lifecycleEpoch;
    }

    /** @return 世界身份（身份哈希 + 维度 id）；无世界时为 0 */
    private static long worldIdentity() {
        World world = Minecraft.getMinecraft().theWorld;
        if (world == null) {
            return 0L;
        }
        long dimension = world.provider == null ? 0L : (long) world.provider.dimensionId;
        return ((long) System.identityHashCode(world) << 32) | (dimension & 0xFFFFFFFFL);
    }

    /** @return 配置 epoch；无客户端提交时为 0（未接线） */
    private static long configRevision() {
        CommittedSnapshot committed = ConfigBootstrap.currentCommittedSnapshot();
        return committed == null ? 0L : committed.epoch;
    }

    /** @return 对象组 revision；状态服务未初始化时为 0 */
    private static long objectGroupRevision() {
        return MyMod.chainStateService == null
            ? 0L
            : MyMod.chainStateService.getClientState().getServerObjectGroupRevision();
    }

    /** @return 服务端 round id；客户端当前无来源，恒 0（未接线） */
    private static long serverRoundId() {
        return 0L;
    }
}
