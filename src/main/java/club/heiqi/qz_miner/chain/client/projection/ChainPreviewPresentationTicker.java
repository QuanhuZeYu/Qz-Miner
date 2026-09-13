package club.heiqi.qz_miner.chain.client.projection;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.ChainPreviewBackendDiagnostics;
import club.heiqi.qz_miner.chain.client.ChainPreviewController;
import club.heiqi.qz_miner.chain.client.ChainPreviewRenderer;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.ChainPreviewVisualSettings;
import club.heiqi.qz_miner.config.CommittedSnapshot;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/**
 * B1.1 表现投影的装配采样器：客户端 {@link TickEvent.ClientTickEvent#END} 采样一次并发布
 * {@link ChainPreviewPresentationProjection} 的 O(1) header。
 *
 * <p>数据源全部是 O(1) 只读访问器：链预览状态 / 控制器远端在途状态 / 客户端阶段投影 /
 * 世界身份 / 生命周期 epoch / 配置 epoch / 对象组 revision / 截断开关位。采样在主线程 tick，
 * 不触碰渲染帧路径。</p>
 *
 * <p>B5.2 追加执行进度数据源：{@link ChainPreviewExecutionProgress} 每 tick 按预算扫描本代目标坐标
 * （区块已加载且方块已消失 → 经 clientChainEventBus 投递 {@code BlockBreakObserved} 观测），
 * 计数在下一次 clientChainEventBus.drain（ClientTickEvent.START）内完成，故 header 里的
 * executedCount 可能比本 tick 的世界状态晚一拍（≤1 tick，可接受）。关闭档不采样不计数、零开销。</p>
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
    /** B5.2：执行进度采样与计数（长寿命单例，随 ticker 装配一次）。 */
    private final ChainPreviewExecutionProgress executionProgress = new ChainPreviewExecutionProgress();
    private boolean registered;
    /** 世界探针缓存：同一世界实例复用（避免每 tick 重建探针对象）。 */
    private World probeWorld;
    private ChainPreviewExecutionProgress.WorldProbe worldProbe;
    /** 最近一次发布的后端诊断（值未变时复用同一实例，稳态零分配）。 */
    private ChainPreviewBackendDiagnostics lastBackendDiagnostics = ChainPreviewBackendDiagnostics.DISABLED;

    /**
     * @param projection 待采样的投影实例
     */
    public ChainPreviewPresentationTicker(ChainPreviewPresentationProjection projection) {
        this.projection = projection;
    }

    /** 注册到 FML 客户端事件总线（客户端 init 调用一次；同时装配执行进度订阅）。 */
    public void register() {
        if (registered) {
            return;
        }
        registered = true;
        executionProgress.install(MyMod.clientChainEventBus);
        FMLCommonHandler.instance().bus().register(this);
    }

    /** @return 执行进度数据源（诊断/测试用） */
    ChainPreviewExecutionProgress getExecutionProgress() {
        return executionProgress;
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
        ChainPreviewController controller = ClientProxy.chainPreviewController;
        if (controller == null) {
            return projection.currentHeader();
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        World world = minecraft.theWorld;
        ChainPreviewState previewState = controller.getPreviewState();
        long worldIdentity = worldIdentity(world);
        boolean executionProgressEnabled = Config.clientPreviewExecutionProgress;
        executionProgress.sample(
            previewState,
            executionProgressEnabled ? probeFor(world) : null,
            worldIdentity,
            lifecycleEpoch,
            executionProgressEnabled,
            minecraft.thePlayer == null ? null : minecraft.thePlayer.getUniqueID());
        ChainPreviewBackendDiagnostics backendDiagnostics = currentBackendDiagnostics();
        return projection.sampleAndPublish(
            previewState,
            controller,
            ClientProxy.clientPhaseProjection,
            worldIdentity,
            lifecycleEpoch,
            serverRoundId(),
            configRevision(),
            objectGroupRevision(),
            ChainPreviewVisualSettings.current().isTruncationSignalEnabled(),
            executionProgressEnabled,
            executionProgress.getExecutedCount(),
            backendDiagnostics);
    }

    /**
     * 采样预览后端诊断（Q4）：开关关闭时恒返回 {@link ChainPreviewBackendDiagnostics#DISABLED}，
     * 且不触碰渲染线程对象；打开时读渲染线程发布的 volatile 快照。
     *
     * <p>零分配口径：值与上次相同时复用上一实例（{@code equals} 值语义），
     * 只在「后端切换 / 首次就绪 / 回退 / 路径不可用」时重建。</p>
     */
    private ChainPreviewBackendDiagnostics currentBackendDiagnostics() {
        if (!Config.clientPreviewBackendDiagnostics) {
            lastBackendDiagnostics = ChainPreviewBackendDiagnostics.DISABLED;
            return lastBackendDiagnostics;
        }
        ChainPreviewRenderer previewRenderer = ClientProxy.chainPreviewRenderer;
        String activeBackendId = previewRenderer == null ? "" : previewRenderer.describeActiveBackendId();
        String fallbackReason = previewRenderer == null ? "" : previewRenderer.describeBackendFallbackReason();
        ChainPreviewBackendDiagnostics next =
            ChainPreviewBackendDiagnostics.of(true, activeBackendId, fallbackReason);
        if (next.equals(lastBackendDiagnostics)) {
            return lastBackendDiagnostics;
        }
        lastBackendDiagnostics = next;
        return next;
    }

    /** @return 该世界的采样探针（同一世界实例复用） */
    private ChainPreviewExecutionProgress.WorldProbe probeFor(World world) {
        if (world == null) {
            probeWorld = null;
            worldProbe = null;
            return null;
        }
        if (world != probeWorld || worldProbe == null) {
            probeWorld = world;
            worldProbe = new MinecraftWorldProbe(world);
        }
        return worldProbe;
    }

    /**
     * 生产世界探针：区块未加载或 y 越界 → 不可判定返回 false（保守不计）；方块为空气/air 材质 → 已破坏。
     */
    private static final class MinecraftWorldProbe implements ChainPreviewExecutionProgress.WorldProbe {

        private final World world;
        private final int dimensionId;

        private MinecraftWorldProbe(World world) {
            this.world = world;
            this.dimensionId = world.provider == null ? 0 : world.provider.dimensionId;
        }

        @Override
        public boolean isDestroyed(int x, int y, int z) {
            if (!world.blockExists(x, y, z)) {
                return false;
            }
            Block block = world.getBlock(x, y, z);
            return block == null || block == Blocks.air || block.getMaterial() == Material.air;
        }

        @Override
        public int getDimensionId() {
            return dimensionId;
        }
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
    private static long worldIdentity(World world) {
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
