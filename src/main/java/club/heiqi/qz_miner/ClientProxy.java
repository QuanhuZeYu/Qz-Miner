package club.heiqi.qz_miner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import club.heiqi.qz_miner.chain.client.ChainPreviewController;
import club.heiqi.qz_miner.chain.client.ChainPreviewRenderer;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjectionSubscriber;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ClientChainEventBusDrainer;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.client.ClientChainConfigSyncDispatch;
import club.heiqi.qz_miner.client.ClientConfigChangeListener;
import club.heiqi.qz_miner.client.ClientConnectionLifecycle;
import club.heiqi.qz_miner.client.ClientConnectionListener;
import club.heiqi.qz_miner.client.ClientMainThreadDispatcher;
import club.heiqi.qz_miner.client.KeyListener;
import club.heiqi.qz_miner.client.QzMinerHudSnapshotProvider;
import club.heiqi.qz_miner.client.RateLimitedRejectDiagnostics;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientAdapter;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapHooks;
import club.heiqi.qz_miner.client.toolswap.ClientAutoToolSwapPacketDispatch;
import club.heiqi.qz_miner.client.toolswap.QzAutoToolSwapClientTransport;
import club.heiqi.qz_miner.client.toolswap.ToolSwapMinecraftFacade;
import club.heiqi.uilib.ui.hud.api.CompactHud;
import club.heiqi.uilib.ui.hud.api.HudAnchor;
import club.heiqi.uilib.ui.hud.api.HudRegistration;
import club.heiqi.qz_miner.network.ObjectGroupWireConfig;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import net.minecraft.network.INetHandler;

public class ClientProxy extends CommonProxy {

    private static final long CONFIG_SYNC_REJECT_DIAG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(10L);
    private static final RateLimitedRejectDiagnostics CONFIG_SYNC_REJECT_DIAG =
            new RateLimitedRejectDiagnostics(CONFIG_SYNC_REJECT_DIAG_INTERVAL_NS);

    private static final ClientChainConfigSyncDispatch.LifecycleGate LIFECYCLE_GATE =
            new ClientChainConfigSyncDispatch.LifecycleGate() {
                @Override
                public boolean isActive(Object token) {
                    return token instanceof ClientConnectionLifecycle.Token
                            && ((ClientConnectionLifecycle.Token) token).isConnectionActive();
                }

                @Override
                public boolean isCurrentAndActive(Object token) {
                    if (!(token instanceof ClientConnectionLifecycle.Token)) {
                        return false;
                    }
                    return ClientConnectionLifecycle.isConnectionCurrentAndActive(
                            (ClientConnectionLifecycle.Token) token);
                }

                @Override
                public boolean publishIfCurrentAndActive(Object token, Runnable publication) {
                    if (!(token instanceof ClientConnectionLifecycle.Token)) {
                        return false;
                    }
                    return ClientConnectionLifecycle.publishIfConnectionCurrentAndActive(
                            (ClientConnectionLifecycle.Token) token, publication);
                }
            };

    /** 自动工具 S2C 只允许在连接与世界均为当前时发布到 adapter。 */
    private static final ClientAutoToolSwapPacketDispatch.LifecycleGate AUTO_TOOL_SWAP_LIFECYCLE_GATE =
            new ClientAutoToolSwapPacketDispatch.LifecycleGate() {
                @Override
                public boolean isActive(Object token) {
                    return token instanceof ClientConnectionLifecycle.Token
                            && ((ClientConnectionLifecycle.Token) token).isWorldActive();
                }

                @Override
                public boolean publishIfCurrentAndActive(Object token, Runnable publication) {
                    return token instanceof ClientConnectionLifecycle.Token
                            && ClientConnectionLifecycle.runIfWorldCurrentAndActive(
                                    (ClientConnectionLifecycle.Token) token, publication);
                }
            };

    public static ChainPreviewController chainPreviewController;
    public static ChainPreviewRenderer chainPreviewRenderer;
    /** 阶段6：客户端连锁阶段投影容器（单玩家，P1-2=A）。 */
    public static ClientPhaseProjection clientPhaseProjection;
    /** 阶段6：客户端投影事件订阅者（订阅 clientChainEventBus 上的 ChainPhaseChanged）。 */
    public static ClientPhaseProjectionSubscriber clientPhaseProjectionSubscriber;
    /** Qz-Miner 紧凑 HUD 的 UILib 注册句柄。 */
    public static HudRegistration chainStatusHudRegistration;
    /** 新版自动工具换位唯一长寿命客户端 adapter。 */
    public static AutoToolSwapClientAdapter autoToolSwapAdapter;

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        // 守决策4：客户端独立事件总线，仅空跑 drain 骨架；不 publish 客户端事件、不持有独立状态机
        // 预览订阅留阶段6接入；bindMainThread 锁定客户端主线程（ClientProxy.init 在客户端主线程执行）
        MyMod.clientChainEventBus = new ChainEventBus();
        MyMod.clientChainEventBus.bindMainThread(Thread.currentThread());
        new ClientChainEventBusDrainer(MyMod.clientChainEventBus).bootstrap();
        // 阶段6 A2：客户端投影容器 + 订阅者（订阅 clientChainEventBus 上的 ChainPhaseChanged，
        // ClientTickEvent.START drain 更新容器，守 I4 主线程收口）
        clientPhaseProjection = new ClientPhaseProjection();
        clientPhaseProjectionSubscriber = new ClientPhaseProjectionSubscriber(
                MyMod.clientChainEventBus, clientPhaseProjection);
        autoToolSwapAdapter = new AutoToolSwapClientAdapter(
                Config.autoToolSwapEnabled,
                Config.autoToolTakeoverEnabled,
                Config.autoToolPrioritySelectors,
                new ToolSwapMinecraftFacade(),
                new QzAutoToolSwapClientTransport());
        AutoToolSwapHooks.install(autoToolSwapAdapter);
        chainPreviewController = new ChainPreviewController();
        chainPreviewController.register();
        chainPreviewRenderer = new ChainPreviewRenderer();
        chainPreviewRenderer.register();
        new ClientConnectionListener().register();
        new ClientConfigChangeListener().register();
        chainStatusHudRegistration = CompactHud.register(
                "qz_miner:chain-status",
                HudAnchor.TOP_LEFT,
                new QzMinerHudSnapshotProvider(MyMod.chainStateService.getClientState(), clientPhaseProjection));
        new KeyListener(autoToolSwapAdapter).register();
    }

    /**
     * 阶段8 块3 F3-a：处理客户端连锁配置同步下发。
     *
     * <p>本方法由 {@code PacketChainConfigSync.Handler} 在 Netty 线程调用。按
     * {@code netHandler} 对象 identity 捕获 connection token（非全局 capture），
     * 不匹配/inactive 直接丢弃。再经 {@link ClientMainThreadDispatcher} 投递。
     * 主线程任务内：先整包校验，再在 lifecycle 线性化边界内复核 connection 仍 current+active
     * 并写 ChainClientState 三字段。旧连接包在 B 已 connect 后即使全局 current=B 也丢弃。</p>
     *
     * <p>守 I4：volatile 只提供可见性，不授予 Netty 线程客户端状态写主权。
     * publication 回调禁阻塞、禁反向调用 lifecycle 入口。</p>
     *
     * @param chainRadius        服务端连锁半径上限
     * @param chainMaxBlocks     服务端连锁目标数上限
     * @param matchedTargetCount 已匹配目标数
     * @param netHandler         入包连接 identity（ctx.netHandler）
     */
    @Override
    public void handleClientChainConfigSync(
            int chainRadius, int chainMaxBlocks, int matchedTargetCount, INetHandler netHandler) {
        final ClientConnectionLifecycle.Token capturedToken =
                ClientConnectionLifecycle.captureForConnection(netHandler);
        // identity 不匹配或连接 inactive：intentional drop，非 dispatcher rejection
        if (capturedToken == null) {
            return;
        }
        final int receivedRadius = chainRadius;
        final int receivedMaxBlocks = chainMaxBlocks;
        final int receivedMatchedTargetCount = matchedTargetCount;
        boolean accepted = ClientChainConfigSyncDispatch.dispatch(
                receivedRadius,
                receivedMaxBlocks,
                receivedMatchedTargetCount,
                capturedToken,
                LIFECYCLE_GATE,
                new ClientChainConfigSyncDispatch.Dispatcher() {
                    @Override
                    public boolean dispatch(Runnable task) {
                        return ClientMainThreadDispatcher.tryRun(task);
                    }
                },
                new ClientChainConfigSyncDispatch.Publication() {
                    @Override
                    public void publish(int radius, int maxBlocks, int matchedCount) {
                        if (MyMod.chainStateService == null) {
                            return;
                        }
                        MyMod.chainStateService.getClientState().setServerChainRadius(radius);
                        MyMod.chainStateService.getClientState().setServerChainMaxBlocks(maxBlocks);
                        MyMod.chainStateService.getClientState().setServerMatchedTargetCount(matchedCount);
                    }
                });
        if (!accepted) {
            noteConfigSyncDispatchRejected();
        }
    }

    /** 自动工具 round 回包：Netty 仅捕获字段，世界级 gate 内交给 adapter。 */
    @Override
    public void handleClientAutoToolSwapRoundResult(
            final int protocolVersion, final long clientNonce, final long serverRoundId,
            final int resultCode, final int roundState, final long nextActionSequence,
            final long serverTick, final boolean rawValid, INetHandler netHandler) {
        final ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.captureForConnection(netHandler);
        ClientAutoToolSwapPacketDispatch.dispatch(token, AUTO_TOOL_SWAP_LIFECYCLE_GATE,
                new ClientAutoToolSwapPacketDispatch.Dispatcher() {
                    @Override
                    public boolean dispatch(Runnable task) {
                        return ClientMainThreadDispatcher.tryRun(task);
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        if (autoToolSwapAdapter != null) {
                            autoToolSwapAdapter.onRoundResult(protocolVersion, clientNonce, serverRoundId,
                                    resultCode, roundState, nextActionSequence, serverTick, rawValid);
                        }
                    }
                });
    }

    /** 自动工具动作回包：仅精确 in-flight 结算会推进 controller。 */
    @Override
    public void handleClientAutoToolSwapActionResult(
            final int protocolVersion, final long serverRoundId, final long actionSequence,
            final int actionCode, final int resultCode, final int roundState, final int anchorSlot,
            final int candidateSlot, final long nextActionSequence, final long serverTick,
            final boolean rawValid, INetHandler netHandler) {
        final ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.captureForConnection(netHandler);
        ClientAutoToolSwapPacketDispatch.dispatch(token, AUTO_TOOL_SWAP_LIFECYCLE_GATE,
                new ClientAutoToolSwapPacketDispatch.Dispatcher() {
                    @Override
                    public boolean dispatch(Runnable task) {
                        return ClientMainThreadDispatcher.tryRun(task);
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        if (autoToolSwapAdapter != null) {
                            autoToolSwapAdapter.onActionResult(protocolVersion, serverRoundId, actionSequence,
                                    actionCode, resultCode, roundState, anchorSlot, candidateSlot,
                                    nextActionSequence, serverTick, rawValid);
                        }
                    }
                });
    }

    /** 自动工具专用 phase 回包：只接受协议层递增 phaseSequence。 */
    @Override
    public void handleClientAutoToolSwapRoundPhase(
            final int protocolVersion, final long serverRoundId, final long phaseSequence,
            final int phaseOrdinal, final int generation, final long serverTick,
            final boolean rawValid, INetHandler netHandler) {
        final ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.captureForConnection(netHandler);
        ClientAutoToolSwapPacketDispatch.dispatch(token, AUTO_TOOL_SWAP_LIFECYCLE_GATE,
                new ClientAutoToolSwapPacketDispatch.Dispatcher() {
                    @Override
                    public boolean dispatch(Runnable task) {
                        return ClientMainThreadDispatcher.tryRun(task);
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        if (autoToolSwapAdapter != null) {
                            autoToolSwapAdapter.onRoundPhase(protocolVersion, serverRoundId, phaseSequence,
                                    phaseOrdinal, generation, serverTick, rawValid);
                        }
                    }
                });
    }

    /** 接替请求只经 world identity gate 发布事实，不在 S2C callback 内扫描库存或发 C2S。 */
    @Override
    public void handleClientAutoToolSwapTakeoverRequest(
            final int protocolVersion, final long serverRoundId, final long actionSequence,
            final int generation, final int targetX, final int targetY, final int targetZ,
            final int targetBlockId, final int targetBlockMetadata, final long serverTick,
            final long deadlineTick, final boolean rawValid, INetHandler netHandler) {
        final ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.captureForConnection(netHandler);
        ClientAutoToolSwapPacketDispatch.dispatch(token, AUTO_TOOL_SWAP_LIFECYCLE_GATE,
                new ClientAutoToolSwapPacketDispatch.Dispatcher() {
                    @Override
                    public boolean dispatch(Runnable task) {
                        return ClientMainThreadDispatcher.tryRun(task);
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        if (autoToolSwapAdapter != null) {
                            autoToolSwapAdapter.onTakeoverRequest(protocolVersion, serverRoundId, actionSequence,
                                    generation, targetX, targetY, targetZ, targetBlockId, targetBlockMetadata,
                                    serverTick, deadlineTick, rawValid);
                        }
                    }
                });
    }

    /**
     * 接收服务端对象组确认：Netty 线程只捕获原始值，主线程校验协议并经 connection gate
     * 更新客户端投影。确认只接受当前本地提交 epoch，结果排序由 ChainClientState 统一收口。
     */
    @Override
    public void handleClientObjectGroupConfigSync(
            int protocolVersion, long requestedRevision, long authoritativeRevision,
            int acceptedFlag, int groupCount, boolean rawValid, INetHandler netHandler) {
        final ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.captureForConnection(netHandler);
        if (token == null) {
            return;
        }
        final int receivedProtocol = protocolVersion;
        final long receivedRequestedRevision = requestedRevision;
        final long receivedAuthoritativeRevision = authoritativeRevision;
        final int receivedAcceptedFlag = acceptedFlag;
        final int receivedGroupCount = groupCount;
        final boolean receivedRawValid = rawValid;
        boolean scheduled = ClientMainThreadDispatcher.tryRun(new Runnable() {
            @Override
            public void run() {
                if (!receivedRawValid
                        || receivedProtocol != ObjectGroupWireConfig.PROTOCOL_VERSION
                        || receivedRequestedRevision < 0L
                        || receivedAuthoritativeRevision < 0L
                        || (receivedAcceptedFlag != 0 && receivedAcceptedFlag != 1)
                        || (receivedAcceptedFlag == 1
                                && receivedRequestedRevision != receivedAuthoritativeRevision)
                        || receivedGroupCount < 0
                        || receivedGroupCount > ObjectGroupWireConfig.MAX_GROUPS) {
                    return;
                }
                ClientConnectionLifecycle.runIfConnectionCurrentAndActive(token, new Runnable() {
                    @Override
                    public void run() {
                        if (MyMod.chainStateService == null) {
                            return;
                        }
                        MyMod.chainStateService.getClientState().applyObjectGroupSyncResult(
                                token.connectionGeneration(), receivedRequestedRevision,
                                receivedAuthoritativeRevision, receivedAcceptedFlag == 1,
                                receivedGroupCount);
                    }
                });
            }
        });
        if (!scheduled) {
            MyMod.LOG.debug("[ObjectGroupSync] Client dispatcher rejected confirmation");
        }
    }

    private static void noteConfigSyncDispatchRejected() {
        CONFIG_SYNC_REJECT_DIAG.note(System.nanoTime(), new RateLimitedRejectDiagnostics.BatchLogger() {
            @Override
            public void log(long batchCount) {
                MyMod.LOG.warn(
                        "[ChainConfigSync] Client dispatcher rejected {} packet(s); dropped without cross-lifecycle retry",
                        Long.valueOf(batchCount));
            }
        });
    }

    /**
     * 测试钩子：拒绝诊断聚合器。
     *
     * @return 配置同步拒绝诊断
     */
    static RateLimitedRejectDiagnostics configSyncRejectDiagnosticsForTests() {
        return CONFIG_SYNC_REJECT_DIAG;
    }

    /**
     * 处理 LootGames 扫雷预览响应。
     *
     * <p>Netty 线程按 netHandler 捕获 token；主线程在 world-active gate 内应用 preview。
     * 旧连接/旧世界包 no-op。</p>
     *
     * @param requestId  请求 id
     * @param origin     原点
     * @param targets    目标
     * @param netHandler 入包连接 identity
     */
    @Override
    public void handleClientLootGamesMinesweeperPreview(
            int requestId, ChainTarget origin, List<ChainTarget> targets, INetHandler netHandler) {
        final ClientConnectionLifecycle.Token capturedToken =
                ClientConnectionLifecycle.captureForConnection(netHandler);
        if (capturedToken == null || !capturedToken.isWorldActive()) {
            return;
        }
        final int capturedRequestId = requestId;
        final ChainTarget originSnapshot = origin;
        final List<ChainTarget> targetSnapshot =
                targets == null ? new ArrayList<ChainTarget>() : new ArrayList<ChainTarget>(targets);
        ClientMainThreadDispatcher.run(new Runnable() {
            @Override
            public void run() {
                ClientConnectionLifecycle.runIfWorldCurrentAndActive(capturedToken, new Runnable() {
                    @Override
                    public void run() {
                        if (chainPreviewController == null) {
                            return;
                        }
                        chainPreviewController.applyLootGamesMinesweeperPreview(
                                capturedRequestId, originSnapshot, targetSnapshot);
                    }
                });
            }
        });
    }

    /**
     * 阶段6：处理连锁阶段快照下发。
     *
     * <p>Netty 线程按 netHandler 捕获 token 与包数据，不直接改投影容器、不 publish 语义状态。
     * 主线程在 world-active gate 内再 publish 到 clientChainEventBus（守 I4）。</p>
     *
     * @param phaseOrdinal 目标态 ordinal
     * @param generation   转移后的新代际
     * @param serverTick   发布时服务端 tick（诊断）
     * @param netHandler   入包连接 identity
     */
    @Override
    public void handleClientChainPhaseSnapshot(
            int phaseOrdinal, int generation, long serverTick, INetHandler netHandler) {
        final ClientConnectionLifecycle.Token capturedToken =
                ClientConnectionLifecycle.captureForConnection(netHandler);
        if (capturedToken == null || !capturedToken.isWorldActive()) {
            return;
        }
        final int capturedPhaseOrdinal = phaseOrdinal;
        final int capturedGeneration = generation;
        final long capturedServerTick = serverTick;
        ClientMainThreadDispatcher.run(new Runnable() {
            @Override
            public void run() {
                ClientConnectionLifecycle.runIfWorldCurrentAndActive(capturedToken, new Runnable() {
                    @Override
                    public void run() {
                        if (MyMod.clientChainEventBus == null) {
                            return;
                        }
                        ChainPhase[] phases = ChainPhase.values();
                        ChainPhase toPhase = capturedPhaseOrdinal >= 0 && capturedPhaseOrdinal < phases.length
                                ? phases[capturedPhaseOrdinal]
                                : ChainPhase.IDLE;
                        // from 占位 IDLE：订阅者只消费 toPhase；gate 内短小 offer，禁阻塞
                        MyMod.clientChainEventBus.publish(new ChainPhaseChanged(
                                null,
                                capturedGeneration,
                                ChainPhase.IDLE,
                                toPhase,
                                capturedServerTick,
                                System.nanoTime()));
                    }
                });
            }
        });
    }
}
