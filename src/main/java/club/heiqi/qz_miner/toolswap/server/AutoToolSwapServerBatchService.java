package club.heiqi.qz_miner.toolswap.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.config.CommittedSnapshot;
import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 普通 CHAIN/AREA 的服务端本地批量自动工具服务。
 *
 * <p>本类是仓内唯一 physical ledger owner。wire round、客户端投影与连锁五态都只能观察结果，
 * 不能取得库存写权。所有入口预期在服务端主线程调用；同步只用于封闭重复生命周期入口。</p>
 */
public final class AutoToolSwapServerBatchService {

    /** 单目标本地决策；正常热路不存在网络 WAIT。 */
    public enum PrepareResult { PROCEED, SKIP_TARGET, STOP }

    /** 一次 ordinary batch 的终局。 */
    public enum BatchOutcome { CONTINUE, FINISHED, STOPPED }

    /** 统一恢复入口的固定原因。 */
    public enum CloseCause {
        NATURAL_FINISH,
        KEY_RELEASE,
        PLAN_CANCELLED,
        ORDINARY_STOP,
        WATCHDOG_TIMEOUT,
        LIFECYCLE_CLEANUP,
        LOGOUT,
        RESPAWN,
        DIMENSION_CHANGE,
        CLONE,
        SERVER_STOP
    }

    /** finalizer 对 physical layout 的分类，冲突与不可用绝不伪报成功。 */
    public enum FinalizationStatus {
        NO_SESSION,
        NO_LEDGER,
        RESTORED,
        RESTORED_AFTER_EXCEPTION,
        ALREADY_RESTORED,
        CONFLICT,
        ENDPOINT_UNAVAILABLE,
        MUTATION_NOT_APPLIED,
        MUTATION_UNKNOWN
    }

    /** 候选读取边界的结果。 */
    public enum ScanStatus { READY, TARGET_INVALID, INVENTORY_UNSAFE, BYPASS }

    /** mutation 抛错后只按已知 pre/post image 分类，绝不盲重放。 */
    enum MutationExceptionImage { PRE_IMAGE, POST_IMAGE, UNKNOWN }

    /** 服务端实时候选读取边界。 */
    public interface CandidateSource {
        CandidateScan scan(Object endpoint, ChainTarget target, List<ToolSelector> selectors);

        boolean targetStillMatches(Object endpoint, ChainTarget target, TargetIdentity identity);

        boolean canHarvest(Object endpoint, ChainTarget target);
    }

    /** 只有通过 token 与 live identity gate 后才可创建的库存端口。 */
    public interface InventoryFactory {
        AutoToolSwapInventoryPort create(Object endpoint);
    }

    /** 单个有序候选的纯值快照。 */
    public static final class CandidateSnapshot {
        private final ToolCandidate candidate;
        private final AutoToolSwapStackState stackState;

        public CandidateSnapshot(ToolCandidate candidate, AutoToolSwapStackState stackState) {
            if (candidate == null || stackState == null || stackState.isEmpty()
                    || candidate.slot() < AutoToolSwapProtocol.INVENTORY_FIRST_SLOT
                    || candidate.slot() > AutoToolSwapProtocol.INVENTORY_LAST_SLOT) {
                throw new IllegalArgumentException("candidate snapshot must contain one occupied inventory slot");
            }
            this.candidate = candidate;
            this.stackState = stackState;
        }

        public int slot() { return candidate.slot(); }
        public ToolCandidate candidate() { return candidate; }
        public AutoToolSwapStackState stackState() { return stackState; }
    }

    /** 不含坐标对象或 Minecraft 可变类型的实时目标身份。 */
    public static final class TargetIdentity {
        private final int blockId;
        private final int metadata;

        public TargetIdentity(int blockId, int metadata) {
            if (blockId <= 0 || metadata < 0) {
                throw new IllegalArgumentException("target identity must be a present block and non-negative meta");
            }
            this.blockId = blockId;
            this.metadata = metadata;
        }

        public int blockId() { return blockId; }
        public int metadata() { return metadata; }

        public boolean sameTarget(TargetIdentity other) {
            return other != null && blockId == other.blockId && metadata == other.metadata;
        }
    }

    /** 一次服务端主线程候选扫描的不可变结果。 */
    public static final class CandidateScan {
        private final ScanStatus status;
        private final Object worldIdentity;
        private final int dimension;
        private final int anchorSlot;
        private final TargetIdentity targetIdentity;
        private final AutoToolSwapStackState anchorState;
        private final boolean handEligible;
        private final List<CandidateSnapshot> candidates;

        private CandidateScan(ScanStatus status, Object worldIdentity, int dimension, int anchorSlot,
                TargetIdentity targetIdentity, AutoToolSwapStackState anchorState, boolean handEligible,
                List<CandidateSnapshot> candidates) {
            if (status == null) throw new IllegalArgumentException("scan status must not be null");
            this.status = status;
            this.worldIdentity = worldIdentity;
            this.dimension = dimension;
            this.anchorSlot = anchorSlot;
            this.targetIdentity = targetIdentity;
            this.anchorState = anchorState;
            this.handEligible = handEligible;
            this.candidates = Collections.unmodifiableList(new ArrayList<CandidateSnapshot>(
                    candidates == null ? Collections.<CandidateSnapshot>emptyList() : candidates));
        }

        public static CandidateScan ready(Object worldIdentity, int dimension, int anchorSlot,
                TargetIdentity targetIdentity, AutoToolSwapStackState anchorState, boolean handEligible,
                List<CandidateSnapshot> candidates) {
            if (worldIdentity == null || !AutoToolSwapProtocol.isHotbarSlot(anchorSlot)
                    || targetIdentity == null || anchorState == null) {
                throw new IllegalArgumentException("ready scan must contain complete live identity");
            }
            return new CandidateScan(ScanStatus.READY, worldIdentity, dimension, anchorSlot,
                    targetIdentity, anchorState, handEligible, candidates);
        }

        public static CandidateScan classified(ScanStatus status, Object worldIdentity,
                int dimension, int anchorSlot) {
            if (status == ScanStatus.READY) throw new IllegalArgumentException("READY requires ready factory");
            return new CandidateScan(status, worldIdentity, dimension, anchorSlot, null, null,
                    false, Collections.<CandidateSnapshot>emptyList());
        }

        public ScanStatus status() { return status; }
        public Object worldIdentity() { return worldIdentity; }
        public int dimension() { return dimension; }
        public int anchorSlot() { return anchorSlot; }
        public TargetIdentity targetIdentity() { return targetIdentity; }
        public AutoToolSwapStackState anchorState() { return anchorState; }
        public boolean handEligible() { return handEligible; }
        public List<CandidateSnapshot> candidates() { return candidates; }
    }

    /** begin 时冻结的执行身份。 */
    public static final class BatchContext {
        private final UUID playerId;
        private final Object endpoint;
        private final Object worldIdentity;
        private final int dimension;
        private final int generation;
        private final long serverRoundId;
        private final int anchorSlot;

        public BatchContext(UUID playerId, Object endpoint, Object worldIdentity, int dimension,
                int generation, long serverRoundId, int anchorSlot) {
            if (playerId == null || endpoint == null || worldIdentity == null || generation < 0
                    || serverRoundId < AutoToolSwapProtocol.NO_SERVER_ROUND_ID
                    || !AutoToolSwapProtocol.isHotbarSlot(anchorSlot)) {
                throw new IllegalArgumentException("batch context contains an invalid execution identity");
            }
            this.playerId = playerId;
            this.endpoint = endpoint;
            this.worldIdentity = worldIdentity;
            this.dimension = dimension;
            this.generation = generation;
            this.serverRoundId = serverRoundId;
            this.anchorSlot = anchorSlot;
        }
    }

    /** 每个 ordinary batch 的不可变 mutation token。 */
    public static final class BatchToken {
        private final UUID playerId;
        private final long ownerEpoch;
        private final long batchEpoch;
        private final long serverTick;

        private BatchToken(UUID playerId, long ownerEpoch, long batchEpoch, long serverTick) {
            this.playerId = playerId;
            this.ownerEpoch = ownerEpoch;
            this.batchEpoch = batchEpoch;
            this.serverTick = serverTick;
        }

        public UUID playerId() { return playerId; }
        public long ownerEpoch() { return ownerEpoch; }
        public long serverTick() { return serverTick; }
    }

    /** batch 尾处理结果；terminal completion 不因可见性失败无限阻塞。 */
    public static final class BatchEndResult {
        private final boolean accepted;
        private final boolean completionReleased;
        private final FinalizationResult finalization;

        private BatchEndResult(boolean accepted, boolean completionReleased,
                FinalizationResult finalization) {
            this.accepted = accepted;
            this.completionReleased = completionReleased;
            this.finalization = finalization;
        }

        public boolean accepted() { return accepted; }
        public boolean completionReleased() { return completionReleased; }
        public FinalizationResult finalization() { return finalization; }
    }

    /** finalizer 的不可变结果。 */
    public static final class FinalizationResult {
        private final long ownerEpoch;
        private final FinalizationStatus status;
        private final CloseCause cause;
        private final boolean publicationAttempted;
        private final boolean publicationSucceeded;

        private FinalizationResult(long ownerEpoch, FinalizationStatus status, CloseCause cause,
                boolean publicationAttempted, boolean publicationSucceeded) {
            this.ownerEpoch = ownerEpoch;
            this.status = status;
            this.cause = cause;
            this.publicationAttempted = publicationAttempted;
            this.publicationSucceeded = publicationSucceeded;
        }

        public long ownerEpoch() { return ownerEpoch; }
        public FinalizationStatus status() { return status; }
        public CloseCause cause() { return cause; }
        public boolean publicationAttempted() { return publicationAttempted; }
        public boolean publicationSucceeded() { return publicationSucceeded; }
    }

    /** flush 的纯值计数，便于 server tick 与测试观察。 */
    public static final class PublicationSummary {
        private final int attempted;
        private final int succeeded;

        private PublicationSummary(int attempted, int succeeded) {
            this.attempted = attempted;
            this.succeeded = succeeded;
        }

        public int attempted() { return attempted; }
        public int succeeded() { return succeeded; }
    }

    /** 玩家当前 local owner 的只读快照。 */
    public static final class LocalSessionSnapshot {
        private final long ownerEpoch;
        private final long policyEpoch;
        private final long serverRoundId;
        private final int generation;
        private final int anchorSlot;
        private final boolean ledgerPresent;
        private final int carrierSlot;
        private final boolean recoveryOnly;
        private final boolean visibilityDirty;
        private final long lastPublicationAttemptTick;

        private LocalSessionSnapshot(long ownerEpoch, long policyEpoch, long serverRoundId,
                int generation, int anchorSlot, boolean ledgerPresent, int carrierSlot,
                boolean recoveryOnly, boolean visibilityDirty, long lastPublicationAttemptTick) {
            this.ownerEpoch = ownerEpoch;
            this.policyEpoch = policyEpoch;
            this.serverRoundId = serverRoundId;
            this.generation = generation;
            this.anchorSlot = anchorSlot;
            this.ledgerPresent = ledgerPresent;
            this.carrierSlot = carrierSlot;
            this.recoveryOnly = recoveryOnly;
            this.visibilityDirty = visibilityDirty;
            this.lastPublicationAttemptTick = lastPublicationAttemptTick;
        }

        public long ownerEpoch() { return ownerEpoch; }
        public long policyEpoch() { return policyEpoch; }
        public long serverRoundId() { return serverRoundId; }
        public int generation() { return generation; }
        public int anchorSlot() { return anchorSlot; }
        public boolean hasLedger() { return ledgerPresent; }
        public int carrierSlot() { return carrierSlot; }
        public boolean recoveryOnly() { return recoveryOnly; }
        public boolean visibilityDirty() { return visibilityDirty; }
        public long lastPublicationAttemptTick() { return lastPublicationAttemptTick; }
    }

    private static final DiagnosticSink PRODUCTION_DIAGNOSTICS = new DiagnosticSink() {
        @Override
        public void log(String message) {
            MyMod.LOG.warn(message);
        }
    };

    interface DiagnosticSink { void log(String message); }

    private final Map<UUID, LocalSession> sessions = new HashMap<UUID, LocalSession>();
    private final Map<UUID, PublicationGate> publicationGates = new HashMap<UUID, PublicationGate>();
    private final Map<UUID, FinalizationResult> lastFinalizations =
            new HashMap<UUID, FinalizationResult>();
    private final CandidateSource candidateSource;
    private final InventoryFactory inventoryFactory;
    private final DiagnosticSink diagnosticSink;
    private Policy latestPolicy = Policy.disabled();
    private long lastOwnerEpoch;
    private boolean lifecycleSubscribed;

    /** 创建 Minecraft 主线程候选与库存适配。 */
    public AutoToolSwapServerBatchService() {
        this(new MinecraftAutoToolSwapCandidateSource(), new InventoryFactory() {
            @Override
            public AutoToolSwapInventoryPort create(Object endpoint) {
                return endpoint instanceof EntityPlayerMP
                        ? new MinecraftAutoToolSwapInventoryPort((EntityPlayerMP) endpoint) : null;
            }
        }, PRODUCTION_DIAGNOSTICS);
    }

    /** 可注入纯值边界的包内构造。 */
    AutoToolSwapServerBatchService(CandidateSource candidateSource, InventoryFactory inventoryFactory) {
        this(candidateSource, inventoryFactory, new DiagnosticSink() {
            @Override public void log(String message) { }
        });
    }

    AutoToolSwapServerBatchService(CandidateSource candidateSource, InventoryFactory inventoryFactory,
            DiagnosticSink diagnosticSink) {
        if (candidateSource == null || inventoryFactory == null || diagnosticSink == null) {
            throw new IllegalArgumentException("local service dependencies must not be null");
        }
        this.candidateSource = candidateSource;
        this.inventoryFactory = inventoryFactory;
        this.diagnosticSink = diagnosticSink;
    }

    /** 发布下一 local session 使用的服务器 Authority；活动 session 保留创建时策略。 */
    public synchronized void publishPolicy(CommittedSnapshot committed) {
        if (committed == null || committed.snapshot == null) return;
        publishPolicy(committed.epoch, committed.snapshot.autoToolSwapEnabled,
                committed.snapshot.autoToolPrioritySelectors);
    }

    /** 包内测试策略入口。 */
    synchronized void publishPolicy(long epoch, boolean enabled, List<ToolSelector> selectors) {
        if (epoch < 0L || epoch < latestPolicy.epoch) return;
        latestPolicy = new Policy(epoch, enabled, selectors);
    }

    /** 在状态机之前订阅取消/看门狗/生命周期恢复屏障。 */
    public synchronized void subscribeLifecycle(ChainEventBus bus) {
        if (bus == null || lifecycleSubscribed) return;
        lifecycleSubscribed = true;
        bus.subscribe(PlanCancelled.class, event -> finalizeFromEvent(event.getPlayerUUID(),
                event.getServerRoundId(), event.getGeneration(), false, CloseCause.PLAN_CANCELLED));
        bus.subscribe(WatchdogTimeout.class, event -> finalizeFromEvent(event.getPlayerUUID(),
                event.getServerRoundId(), event.getGeneration(), false, CloseCause.WATCHDOG_TIMEOUT));
        bus.subscribe(LifecycleCleanup.class, event -> finalizeFromEvent(event.getPlayerUUID(),
                event.getServerRoundId(), event.getGeneration(), event.isForced(),
                CloseCause.LIFECYCLE_CLEANUP));
    }

    /** 注册 server tick END 的统一 publication flush。 */
    public void bootstrap() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /** 每 tick END 对每玩家至多尝试一次完整 window 0 publication。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event != null && event.phase == TickEvent.Phase.END) {
            flushPublications(Math.max(0L, ChainTickSource.currentServerTick()));
        }
    }

    /** 生产 ordinary batch 入口。 */
    public BatchToken beginOrdinaryBatch(EntityPlayerMP player, long serverRoundId,
            int generation, long serverTick) {
        if (player == null || player.worldObj == null) return null;
        return beginOrdinaryBatch(new BatchContext(player.getUniqueID(), player, player.worldObj,
                player.dimension, generation, serverRoundId, player.inventory.currentItem), serverTick);
    }

    /** 签发本 tick 的唯一 mutation token；不匹配活动 owner 时 fail closed。 */
    public synchronized BatchToken beginOrdinaryBatch(BatchContext context, long serverTick) {
        if (context == null || serverTick < 0L) return null;
        LocalSession session = sessions.get(context.playerId);
        if (session == null) {
            if (lastOwnerEpoch == Long.MAX_VALUE) return null;
            session = new LocalSession(++lastOwnerEpoch, context, latestPolicy);
            sessions.put(context.playerId, session);
            lastFinalizations.remove(context.playerId);
        } else if (!session.matches(context) || session.recoveryOnly || session.finalizing) {
            return null;
        }
        if (session.lastBatchEpoch == Long.MAX_VALUE) return null;
        session.lastBatchEpoch++;
        session.activeBatchEpoch = session.lastBatchEpoch;
        session.activeBatchTick = serverTick;
        return new BatchToken(context.playerId, session.ownerEpoch, session.activeBatchEpoch, serverTick);
    }

    /**
     * 每目标实时读取目标与库存并在本地完成零次或一次 mutation。任何网络包都不参与返回值。
     */
    public synchronized PrepareResult prepareTarget(BatchToken token, ChainTarget target, long serverTick) {
        LocalSession session = matchingActiveSession(token, serverTick);
        if (session == null) return PrepareResult.STOP;
        if (target == null || session.recoveryOnly) return session.recoveryOnly
                ? PrepareResult.STOP : PrepareResult.SKIP_TARGET;
        if (!session.policy.enabled) return harvestAuthority(session.endpoint, target);

        CandidateScan scan;
        try {
            scan = candidateSource.scan(session.endpoint, target, session.policy.selectors);
        } catch (RuntimeException failure) {
            return PrepareResult.SKIP_TARGET;
        } catch (LinkageError failure) {
            return PrepareResult.SKIP_TARGET;
        }
        if (scan == null || scan.worldIdentity() != session.worldIdentity
                || scan.dimension() != session.dimension || scan.anchorSlot() != session.anchorSlot) {
            return PrepareResult.STOP;
        }
        if (scan.status() == ScanStatus.INVENTORY_UNSAFE) return PrepareResult.STOP;
        if (scan.status() == ScanStatus.TARGET_INVALID) return PrepareResult.SKIP_TARGET;
        if (scan.status() == ScanStatus.BYPASS) {
            return harvestAuthority(session.endpoint, target);
        }
        if (scan.handEligible()) return harvestAuthority(session.endpoint, target);

        CandidateSnapshot selected = scan.candidates().isEmpty() ? null : scan.candidates().get(0);
        if (selected == null) {
            if (session.ledger != null) {
                PrepareResult restored = restoreSegment(session, scan.targetIdentity(), target);
                if (restored != PrepareResult.PROCEED) return restored;
            }
            return harvestAuthority(session.endpoint, target);
        }
        if (session.ledger != null && selected.slot() == session.ledger.carrierSlot) {
            PrepareResult restored = restoreSegment(session, scan.targetIdentity(), target);
            return restored == PrepareResult.PROCEED
                    ? harvestAuthority(session.endpoint, target) : restored;
        }

        PrepareResult mutation = session.ledger == null
                ? firstSwap(session, scan, selected, target)
                : rotateBorrowedTool(session, scan, selected, target);
        if (mutation != PrepareResult.PROCEED) return mutation;
        return harvestAuthority(session.endpoint, target);
    }

    /** batch 结束；terminal 路径先恢复并尝试最终 publication，再释放连锁 completion。 */
    public synchronized BatchEndResult endOrdinaryBatch(BatchToken token, BatchOutcome outcome,
            long serverTick) {
        LocalSession session = matchingActiveSession(token, serverTick);
        if (session == null || outcome == null) return new BatchEndResult(false, false, null);
        session.activeBatchEpoch = 0L;
        if (outcome == BatchOutcome.CONTINUE) {
            return new BatchEndResult(true, false, null);
        }
        CloseCause cause = outcome == BatchOutcome.FINISHED
                ? CloseCause.NATURAL_FINISH : CloseCause.ORDINARY_STOP;
        FinalizationResult result = finalizeSession(session, session.endpoint, null, cause, serverTick);
        return new BatchEndResult(true, true, result);
    }

    /** 生命周期直接 finalizer；preferred/alternate 支持 clone 的 old/new endpoint。 */
    public synchronized FinalizationResult finalizePlayer(UUID playerId, Object preferredEndpoint,
            Object alternateEndpoint, CloseCause cause, long serverTick) {
        if (playerId == null || cause == null || serverTick < 0L) {
            return new FinalizationResult(0L, FinalizationStatus.NO_SESSION, cause, false, false);
        }
        LocalSession session = sessions.get(playerId);
        if (session == null) {
            FinalizationResult previous = lastFinalizations.get(playerId);
            PublicationAttempt publication = attemptPublication(playerId, preferredEndpoint, serverTick);
            if (previous != null) {
                FinalizationStatus repeated = isSuccessfulFinalization(previous.status)
                        ? FinalizationStatus.ALREADY_RESTORED : previous.status;
                return new FinalizationResult(previous.ownerEpoch, repeated,
                        cause, publication.attempted, publication.succeeded);
            }
            return new FinalizationResult(0L, FinalizationStatus.NO_SESSION, cause,
                    publication.attempted, publication.succeeded);
        }
        return finalizeSession(session, preferredEndpoint, alternateEndpoint, cause, serverTick);
    }

    /** 服务停止兜底：逐 owner 恢复，不能用 map clear 丢弃 ledger。 */
    public synchronized void finalizeAll(CloseCause cause, long serverTick) {
        for (UUID playerId : new ArrayList<UUID>(sessions.keySet())) {
            LocalSession session = sessions.get(playerId);
            if (session != null) finalizeSession(session, session.endpoint, null, cause, serverTick);
        }
    }

    /** 统一 flush；失败只保留无 mutation 权限的 visibility dirty gate。 */
    public synchronized PublicationSummary flushPublications(long serverTick) {
        int attempted = 0;
        int succeeded = 0;
        for (UUID playerId : new ArrayList<UUID>(publicationGates.keySet())) {
            PublicationAttempt result = attemptPublication(playerId, null, serverTick);
            if (result.attempted) attempted++;
            if (result.succeeded) succeeded++;
            PublicationGate gate = publicationGates.get(playerId);
            if (gate != null && !gate.dirty && !sessions.containsKey(playerId)) {
                publicationGates.remove(playerId);
            }
        }
        return new PublicationSummary(attempted, succeeded);
    }

    /** 清理层只能释放已无 ledger 且无 visibility dirty 的完成记录。 */
    public synchronized void releaseFinalizedPlayer(UUID playerId) {
        if (playerId == null || sessions.containsKey(playerId)) return;
        PublicationGate gate = publicationGates.get(playerId);
        if (gate == null || !gate.dirty) {
            publicationGates.remove(playerId);
            lastFinalizations.remove(playerId);
        }
    }

    /** @return 当前 local owner/visibility 的只读快照。 */
    public synchronized LocalSessionSnapshot snapshot(UUID playerId) {
        LocalSession session = sessions.get(playerId);
        PublicationGate gate = publicationGates.get(playerId);
        if (session == null && gate == null) return null;
        return new LocalSessionSnapshot(session == null ? 0L : session.ownerEpoch,
                session == null ? 0L : session.policy.epoch,
                session == null ? 0L : session.serverRoundId,
                session == null ? 0 : session.generation,
                session == null ? -1 : session.anchorSlot,
                session != null && session.ledger != null,
                session == null || session.ledger == null ? -1 : session.ledger.carrierSlot,
                session != null && session.recoveryOnly,
                gate != null && gate.dirty,
                gate == null ? -1L : gate.lastAttemptTick);
    }

    private PrepareResult firstSwap(LocalSession session, CandidateScan scan,
            CandidateSnapshot selected, ChainTarget target) {
        if (selected.slot() == session.anchorSlot) return PrepareResult.SKIP_TARGET;
        AutoToolSwapInventoryPort inventory = createSafeInventory(session);
        if (inventory == null) return PrepareResult.STOP;
        AutoToolSwapStackState anchor = read(inventory, session.anchorSlot);
        AutoToolSwapStackState candidate = read(inventory, selected.slot());
        if (!sameContent(anchor, scan.anchorState()) || !sameContent(candidate, selected.stackState())
                || candidate == null || candidate.isEmpty()
                || !targetMatches(session, target, scan.targetIdentity())) {
            return PrepareResult.SKIP_TARGET;
        }
        PhysicalLedger after = new PhysicalLedger(session.anchorSlot, selected.slot(), anchor, candidate);
        MutationPlan plan = MutationPlan.swap(session.anchorSlot, selected.slot(), anchor, candidate, after);
        return executeMutation(session, inventory, plan);
    }

    private PrepareResult rotateBorrowedTool(LocalSession session, CandidateScan scan,
            CandidateSnapshot selected, ChainTarget target) {
        PhysicalLedger ledger = session.ledger;
        if (ledger == null || selected.slot() == ledger.carrierSlot) return PrepareResult.STOP;
        if (selected.slot() == session.anchorSlot) return PrepareResult.SKIP_TARGET;
        AutoToolSwapInventoryPort inventory = createSafeInventory(session);
        if (inventory == null) return PrepareResult.STOP;
        AutoToolSwapStackState anchor = read(inventory, session.anchorSlot);
        AutoToolSwapStackState carrier = read(inventory, ledger.carrierSlot);
        AutoToolSwapStackState candidate = read(inventory, selected.slot());
        if (!knownBorrowedLayout(ledger, anchor, carrier)) {
            session.recoveryOnly = true;
            session.conflict = true;
            return PrepareResult.STOP;
        }
        if (!sameContent(candidate, selected.stackState()) || candidate == null || candidate.isEmpty()
                || !targetMatches(session, target, scan.targetIdentity())) {
            return PrepareResult.SKIP_TARGET;
        }
        PhysicalLedger after = new PhysicalLedger(session.anchorSlot, selected.slot(),
                ledger.originalAnchorRole, candidate);
        MutationPlan plan = MutationPlan.rotate(session.anchorSlot, ledger.carrierSlot, selected.slot(),
                anchor, carrier, candidate, after);
        return executeMutation(session, inventory, plan);
    }

    /** 无候选或 candidate==carrier 时恢复当前 segment，不把旧借用角色继续占着主手。 */
    private PrepareResult restoreSegment(LocalSession session, TargetIdentity targetIdentity, ChainTarget target) {
        PhysicalLedger ledger = session.ledger;
        if (ledger == null) return PrepareResult.PROCEED;
        AutoToolSwapInventoryPort inventory = createSafeInventory(session);
        if (inventory == null) return PrepareResult.STOP;
        AutoToolSwapStackState anchor = read(inventory, ledger.anchorSlot);
        AutoToolSwapStackState carrier = read(inventory, ledger.carrierSlot);
        if (!knownBorrowedLayout(ledger, anchor, carrier)
                || !targetMatches(session, target, targetIdentity)) {
            session.recoveryOnly = true;
            return PrepareResult.STOP;
        }
        return executeMutation(session, inventory,
                MutationPlan.swap(ledger.anchorSlot, ledger.carrierSlot, anchor, carrier, null));
    }

    private PrepareResult executeMutation(LocalSession session, AutoToolSwapInventoryPort inventory,
            MutationPlan plan) {
        try {
            if (plan.rotation) {
                inventory.rotateInventorySlotsAtomically(plan.slots[0], plan.slots[1], plan.slots[2]);
            } else {
                inventory.swapInventorySlotsAtomically(plan.slots[0], plan.slots[1]);
            }
        } catch (RuntimeException failure) {
            return classifyMutationFailure(session, inventory, plan, failure);
        } catch (LinkageError failure) {
            return classifyMutationFailure(session, inventory, plan, failure);
        }
        commitMutation(session, plan.ledgerAfter);
        return PrepareResult.PROCEED;
    }

    private PrepareResult classifyMutationFailure(LocalSession session, AutoToolSwapInventoryPort inventory,
            MutationPlan plan, Throwable failure) {
        MutationExceptionImage image = classifyImage(inventory, plan);
        diagnose("mutation-exception", session, image + ":" + failure.getClass().getSimpleName());
        if (image == MutationExceptionImage.PRE_IMAGE) return PrepareResult.SKIP_TARGET;
        if (image == MutationExceptionImage.POST_IMAGE) {
            commitMutation(session, plan.ledgerAfter);
            session.recoveryOnly = true;
            return PrepareResult.STOP;
        }
        session.recoveryOnly = true;
        session.conflict = true;
        markVisibilityDirty(session);
        return PrepareResult.STOP;
    }

    private static MutationExceptionImage classifyImage(AutoToolSwapInventoryPort inventory, MutationPlan plan) {
        AutoToolSwapStackState[] current = new AutoToolSwapStackState[plan.slots.length];
        try {
            for (int i = 0; i < plan.slots.length; i++) current[i] = inventory.readInventorySlot(plan.slots[i]);
        } catch (RuntimeException failure) {
            return MutationExceptionImage.UNKNOWN;
        } catch (LinkageError failure) {
            return MutationExceptionImage.UNKNOWN;
        }
        if (sameImage(current, plan.pre)) return MutationExceptionImage.PRE_IMAGE;
        if (sameImage(current, plan.post)) return MutationExceptionImage.POST_IMAGE;
        return MutationExceptionImage.UNKNOWN;
    }

    private void commitMutation(LocalSession session, PhysicalLedger ledgerAfter) {
        session.ledger = ledgerAfter;
        markVisibilityDirty(session);
    }

    private void markVisibilityDirty(LocalSession session) {
        PublicationGate gate = publicationGate(session.playerId);
        gate.dirty = true;
        gate.endpoint = session.endpoint;
    }

    private FinalizationResult finalizeSession(LocalSession session, Object preferredEndpoint,
            Object alternateEndpoint, CloseCause cause, long serverTick) {
        session.finalizing = true;
        FinalizationStatus status = session.ledger == null
                ? session.conflict ? FinalizationStatus.CONFLICT : FinalizationStatus.NO_LEDGER
                : restoreFinalLayout(session, preferredEndpoint, alternateEndpoint);
        sessions.remove(session.playerId);
        PublicationAttempt publication = attemptPublication(session.playerId,
                session.endpoint, serverTick);
        PublicationGate gate = publicationGates.get(session.playerId);
        if (gate != null && !gate.dirty) publicationGates.remove(session.playerId);
        FinalizationResult result = new FinalizationResult(session.ownerEpoch, status, cause,
                publication.attempted, publication.succeeded);
        lastFinalizations.put(session.playerId, result);
        diagnose("finalize", session, cause + ":" + status);
        return result;
    }

    private FinalizationStatus restoreFinalLayout(LocalSession session, Object preferredEndpoint,
            Object alternateEndpoint) {
        PhysicalLedger ledger = session.ledger;
        if (ledger == null) return FinalizationStatus.NO_LEDGER;
        boolean foundEndpoint = false;
        for (Object endpoint : endpointCandidates(session.endpoint, preferredEndpoint, alternateEndpoint)) {
            AutoToolSwapInventoryPort inventory;
            try {
                inventory = inventoryFactory.create(endpoint);
            } catch (RuntimeException failure) {
                continue;
            } catch (LinkageError failure) {
                continue;
            }
            if (inventory == null) continue;
            foundEndpoint = true;
            AutoToolSwapStackState anchor = read(inventory, ledger.anchorSlot);
            AutoToolSwapStackState carrier = read(inventory, ledger.carrierSlot);
            if (anchor == null || carrier == null) continue;
            if (knownBorrowedLayout(ledger, anchor, carrier)) {
                MutationPlan plan = MutationPlan.swap(ledger.anchorSlot, ledger.carrierSlot,
                        anchor, carrier, null);
                session.endpoint = endpoint;
                try {
                    inventory.swapInventorySlotsAtomically(ledger.anchorSlot, ledger.carrierSlot);
                    commitMutation(session, null);
                    return FinalizationStatus.RESTORED;
                } catch (RuntimeException failure) {
                    return classifyFinalRestoreFailure(session, inventory, plan);
                } catch (LinkageError failure) {
                    return classifyFinalRestoreFailure(session, inventory, plan);
                }
            }
            if (knownRestoredLayout(ledger, anchor, carrier)) {
                session.ledger = null;
                session.endpoint = endpoint;
                return FinalizationStatus.ALREADY_RESTORED;
            }
        }
        session.recoveryOnly = true;
        session.conflict = foundEndpoint;
        return foundEndpoint ? FinalizationStatus.CONFLICT : FinalizationStatus.ENDPOINT_UNAVAILABLE;
    }

    private FinalizationStatus classifyFinalRestoreFailure(LocalSession session,
            AutoToolSwapInventoryPort inventory, MutationPlan plan) {
        MutationExceptionImage image = classifyImage(inventory, plan);
        if (image == MutationExceptionImage.POST_IMAGE) {
            commitMutation(session, null);
            return FinalizationStatus.RESTORED_AFTER_EXCEPTION;
        }
        session.recoveryOnly = true;
        if (image == MutationExceptionImage.PRE_IMAGE) return FinalizationStatus.MUTATION_NOT_APPLIED;
        session.conflict = true;
        markVisibilityDirty(session);
        return FinalizationStatus.MUTATION_UNKNOWN;
    }

    private PublicationAttempt attemptPublication(UUID playerId, Object preferredEndpoint, long serverTick) {
        PublicationGate gate = publicationGates.get(playerId);
        if (gate == null || !gate.dirty || gate.lastAttemptTick == serverTick) {
            return PublicationAttempt.NONE;
        }
        gate.lastAttemptTick = serverTick;
        Object endpoint = preferredEndpoint == null ? gate.endpoint : preferredEndpoint;
        try {
            AutoToolSwapInventoryPort inventory = endpoint == null ? null : inventoryFactory.create(endpoint);
            if (inventory == null) return PublicationAttempt.FAILED;
            inventory.syncInventoryDifference();
            gate.dirty = false;
            gate.endpoint = endpoint;
            return PublicationAttempt.SUCCEEDED;
        } catch (RuntimeException failure) {
            return PublicationAttempt.FAILED;
        } catch (LinkageError failure) {
            return PublicationAttempt.FAILED;
        }
    }

    private AutoToolSwapInventoryPort createSafeInventory(LocalSession session) {
        AutoToolSwapInventoryPort inventory;
        try {
            inventory = inventoryFactory.create(session.endpoint);
            if (inventory == null || !inventory.isPlayerAlive() || inventory.isCreativeMode()
                    || !inventory.hasPersonalInventoryWindow0() || !inventory.isCursorEmpty()
                    || inventory.selectedHotbarSlot() != session.anchorSlot) return null;
        } catch (RuntimeException failure) {
            return null;
        } catch (LinkageError failure) {
            return null;
        }
        return inventory;
    }

    private boolean targetMatches(LocalSession session, ChainTarget target, TargetIdentity identity) {
        try {
            return identity != null && candidateSource.targetStillMatches(session.endpoint, target, identity);
        } catch (RuntimeException failure) {
            return false;
        } catch (LinkageError failure) {
            return false;
        }
    }

    private PrepareResult harvestAuthority(Object endpoint, ChainTarget target) {
        try {
            return candidateSource.canHarvest(endpoint, target)
                    ? PrepareResult.PROCEED : PrepareResult.SKIP_TARGET;
        } catch (RuntimeException failure) {
            return PrepareResult.SKIP_TARGET;
        } catch (LinkageError failure) {
            return PrepareResult.SKIP_TARGET;
        }
    }

    private LocalSession matchingActiveSession(BatchToken token, long serverTick) {
        if (token == null || serverTick < 0L || token.serverTick != serverTick) return null;
        LocalSession session = sessions.get(token.playerId);
        return session != null && session.ownerEpoch == token.ownerEpoch
                && session.activeBatchEpoch == token.batchEpoch
                && session.activeBatchTick == serverTick ? session : null;
    }

    private synchronized void finalizeFromEvent(UUID playerId, long serverRoundId, int generation,
            boolean forced, CloseCause cause) {
        LocalSession session = sessions.get(playerId);
        if (!forced && (session == null || session.serverRoundId != serverRoundId
                || session.generation != generation)) return;
        Object endpoint = null;
        if (MyMod.playerManager != null) endpoint = MyMod.playerManager.getPlayer(playerId);
        finalizePlayer(playerId, endpoint, null, cause,
                Math.max(0L, ChainTickSource.currentServerTick()));
    }

    private PublicationGate publicationGate(UUID playerId) {
        PublicationGate gate = publicationGates.get(playerId);
        if (gate == null) {
            gate = new PublicationGate();
            publicationGates.put(playerId, gate);
        }
        return gate;
    }

    private void diagnose(String event, LocalSession session, String detail) {
        try {
            diagnosticSink.log("[AutoToolSwapLocal] event=" + event + " player=" + session.playerId
                    + " owner=" + session.ownerEpoch + " round=" + session.serverRoundId
                    + " detail=" + detail);
        } catch (RuntimeException ignored) {
        } catch (LinkageError ignored) {
        }
    }

    private static AutoToolSwapStackState read(AutoToolSwapInventoryPort inventory, int slot) {
        try {
            return inventory.readInventorySlot(slot);
        } catch (RuntimeException failure) {
            return null;
        } catch (LinkageError failure) {
            return null;
        }
    }

    private static boolean knownBorrowedLayout(PhysicalLedger ledger,
            AutoToolSwapStackState anchor, AutoToolSwapStackState carrier) {
        return ledger != null && anchor != null && carrier != null
                && (anchor.isEmpty() || ledger.activeBorrowedRole.sameRole(anchor))
                && ledger.originalAnchorRole.sameRole(carrier);
    }

    private static boolean knownRestoredLayout(PhysicalLedger ledger,
            AutoToolSwapStackState anchor, AutoToolSwapStackState carrier) {
        return ledger != null && anchor != null && carrier != null
                && ledger.originalAnchorRole.sameRole(anchor)
                && (carrier.isEmpty() || ledger.activeBorrowedRole.sameRole(carrier));
    }

    private static boolean sameContent(AutoToolSwapStackState left, AutoToolSwapStackState right) {
        return left != null && left.sameContent(right);
    }

    private static boolean isSuccessfulFinalization(FinalizationStatus status) {
        return status == FinalizationStatus.NO_LEDGER || status == FinalizationStatus.RESTORED
                || status == FinalizationStatus.RESTORED_AFTER_EXCEPTION
                || status == FinalizationStatus.ALREADY_RESTORED;
    }

    private static boolean sameImage(AutoToolSwapStackState[] left, AutoToolSwapStackState[] right) {
        if (left == null || right == null || left.length != right.length) return false;
        for (int i = 0; i < left.length; i++) if (!sameContent(left[i], right[i])) return false;
        return true;
    }

    private static List<Object> endpointCandidates(Object stored, Object preferred, Object alternate) {
        Set<Object> unique = new LinkedHashSet<Object>();
        if (preferred != null) unique.add(preferred);
        if (alternate != null) unique.add(alternate);
        if (stored != null) unique.add(stored);
        return new ArrayList<Object>(unique);
    }

    /** 创建时冻结的服务器策略。 */
    private static final class Policy {
        private final long epoch;
        private final boolean enabled;
        private final List<ToolSelector> selectors;

        private Policy(long epoch, boolean enabled, List<ToolSelector> selectors) {
            this.epoch = epoch;
            this.enabled = enabled;
            this.selectors = Collections.unmodifiableList(new ArrayList<ToolSelector>(
                    selectors == null ? Collections.<ToolSelector>emptyList() : selectors));
        }

        private static Policy disabled() {
            return new Policy(0L, false, Collections.<ToolSelector>emptyList());
        }
    }

    /** 单玩家 local session；唯一 physical ledger 字段只存在于本类。 */
    private static final class LocalSession {
        private final UUID playerId;
        private Object endpoint;
        private final Object worldIdentity;
        private final int dimension;
        private final int generation;
        private final long serverRoundId;
        private final int anchorSlot;
        private final long ownerEpoch;
        private final Policy policy;
        private long lastBatchEpoch;
        private long activeBatchEpoch;
        private long activeBatchTick;
        private PhysicalLedger ledger;
        private boolean recoveryOnly;
        private boolean conflict;
        private boolean finalizing;

        private LocalSession(long ownerEpoch, BatchContext context, Policy policy) {
            this.playerId = context.playerId;
            this.endpoint = context.endpoint;
            this.worldIdentity = context.worldIdentity;
            this.dimension = context.dimension;
            this.generation = context.generation;
            this.serverRoundId = context.serverRoundId;
            this.anchorSlot = context.anchorSlot;
            this.ownerEpoch = ownerEpoch;
            this.policy = policy;
        }

        private boolean matches(BatchContext context) {
            return context != null && playerId.equals(context.playerId) && endpoint == context.endpoint
                    && worldIdentity == context.worldIdentity && dimension == context.dimension
                    && generation == context.generation && serverRoundId == context.serverRoundId
                    && anchorSlot == context.anchorSlot;
        }
    }

    /** 二槽/三槽/segment/final restore 共用的唯一物理角色账本。 */
    private static final class PhysicalLedger {
        private final int anchorSlot;
        private final int carrierSlot;
        private final AutoToolSwapStackState originalAnchorRole;
        private final AutoToolSwapStackState activeBorrowedRole;

        private PhysicalLedger(int anchorSlot, int carrierSlot,
                AutoToolSwapStackState originalAnchorRole,
                AutoToolSwapStackState activeBorrowedRole) {
            this.anchorSlot = anchorSlot;
            this.carrierSlot = carrierSlot;
            this.originalAnchorRole = originalAnchorRole;
            this.activeBorrowedRole = activeBorrowedRole;
        }
    }

    /** 一次 mutation 的 immutable pre/post image 与 ledger-after。 */
    private static final class MutationPlan {
        private final int[] slots;
        private final AutoToolSwapStackState[] pre;
        private final AutoToolSwapStackState[] post;
        private final boolean rotation;
        private final PhysicalLedger ledgerAfter;

        private MutationPlan(int[] slots, AutoToolSwapStackState[] pre,
                AutoToolSwapStackState[] post, boolean rotation, PhysicalLedger ledgerAfter) {
            this.slots = slots;
            this.pre = pre;
            this.post = post;
            this.rotation = rotation;
            this.ledgerAfter = ledgerAfter;
        }

        private static MutationPlan swap(int first, int second, AutoToolSwapStackState firstState,
                AutoToolSwapStackState secondState, PhysicalLedger ledgerAfter) {
            return new MutationPlan(new int[] { first, second },
                    new AutoToolSwapStackState[] { firstState, secondState },
                    new AutoToolSwapStackState[] { secondState, firstState }, false, ledgerAfter);
        }

        private static MutationPlan rotate(int anchor, int oldCarrier, int nextCarrier,
                AutoToolSwapStackState anchorState, AutoToolSwapStackState oldCarrierState,
                AutoToolSwapStackState nextCarrierState, PhysicalLedger ledgerAfter) {
            return new MutationPlan(new int[] { anchor, oldCarrier, nextCarrier },
                    new AutoToolSwapStackState[] { anchorState, oldCarrierState, nextCarrierState },
                    new AutoToolSwapStackState[] { nextCarrierState, anchorState, oldCarrierState },
                    true, ledgerAfter);
        }
    }

    /** publication 与 mutation owner 解耦的无权 tombstone。 */
    private static final class PublicationGate {
        private boolean dirty;
        private Object endpoint;
        private long lastAttemptTick = -1L;
    }

    private static final class PublicationAttempt {
        private static final PublicationAttempt NONE = new PublicationAttempt(false, false);
        private static final PublicationAttempt FAILED = new PublicationAttempt(true, false);
        private static final PublicationAttempt SUCCEEDED = new PublicationAttempt(true, true);
        private final boolean attempted;
        private final boolean succeeded;

        private PublicationAttempt(boolean attempted, boolean succeeded) {
            this.attempted = attempted;
            this.succeeded = succeeded;
        }
    }
}
