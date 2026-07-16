package club.heiqi.qz_miner.chain.planner;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 规划运行时装配工厂。
 */
public final class ChainPlanningRuntimeFactory {

    /** 每个 planning round 最多输出的候选明细数。 */
    public static final int DEFAULT_DIAGNOSTIC_CANDIDATE_BUDGET = 12;

    private ChainPlanningRuntimeFactory() {}

    public static ChainPlanningRuntime createForServer(
        World world,
        EntityPlayer player,
        ChainSession session,
        BlockSeedSnapshot seedSnapshot) {
        return createForServer(world, player, session, seedSnapshot, null);
    }

    /** 使用 round 级有界诊断器装配服务端规划运行时。 */
    public static ChainPlanningRuntime createForServer(
        World world,
        EntityPlayer player,
        ChainSession session,
        BlockSeedSnapshot seedSnapshot,
        PlanningDiagnostics diagnostics) {
        if (world == null || session == null || seedSnapshot == null) {
            return null;
        }

        session.getTraversalTargets().clear();
        int requestedRadius = session.getRequest().getRequestedChainRadius();
        int requestedMaxBlocks = session.getRequest().getRequestedChainMaxBlocks();
        int effectiveRadius = requestedRadius > 0 ? Math.min(Config.chainRadius, requestedRadius) : Config.chainRadius;
        int effectiveMaxBlocks = requestedMaxBlocks > 0 ? Math.min(Config.chainMaxBlocks, requestedMaxBlocks) : Config.chainMaxBlocks;
        ChainSearchContext searchContext = createSearchContext(
            world,
            seedSnapshot,
            session.getRequest().getSubMode(),
            effectiveRadius,
            effectiveMaxBlocks,
            session.getTraversalTargets(), session.getRequest().getModeExtension());

        return createRuntime(player, session, searchContext, session.getRequest().getMode(), diagnostics);
    }

    public static ChainPlanningRuntime createForPreview(
        World world,
        EntityPlayer player,
        ChainSession session,
        BlockSeedSnapshot seedSnapshot,
        int maxRadius,
        int maxTargets) {
        if (world == null || seedSnapshot == null || session == null) {
            return null;
        }

        ChainSearchContext searchContext = createSearchContext(
            world,
            seedSnapshot,
            session.getRequest().getSubMode(),
            maxRadius,
            maxTargets,
            new ConcurrentLinkedQueue<ChainTarget>(), session.getRequest().getModeExtension());

        return createRuntime(player, session, searchContext, session.getRequest().getMode(), null);
    }

    private static ChainPlanningRuntime createRuntime(
        EntityPlayer player,
        ChainSession session,
        ChainSearchContext searchContext,
        club.heiqi.qz_miner.chain.mode.ChainMode mode,
        PlanningDiagnostics diagnostics) {
        ChainModeDefinition definition = ChainModeRegistry.getDefinition(mode);
        if (definition == null || searchContext == null) {
            return null;
        }
        ChainResolverContext resolverContext = new ChainResolverContext(player, session, searchContext);
        ChainCandidateFilter candidateFilter = createCandidateFilter(searchContext, diagnostics);
        searchContext.setCandidateFilter(candidateFilter);

        BudgetedChainTraverser traverser = definition.createTraverser(resolverContext);
        ChainBlockMatcher matcher = definition.createMatcher(resolverContext);
        if (candidateFilter == null || traverser == null || matcher == null) {
            return null;
        }
        if (matcher instanceof HarvestableBlockMatcher) {
            ((HarvestableBlockMatcher) matcher).setDiagnostics(diagnostics);
        } else if (matcher instanceof SameBlockHarvestableMatcher) {
            ((SameBlockHarvestableMatcher) matcher).setDiagnostics(diagnostics);
        }
        matcher = ModeExtensionMatcherDecorator.decorateMatcher(
            searchContext.getSubMode(), matcher, searchContext.getFrozenModePredicate());
        if (diagnostics != null) {
            final ChainBlockMatcher decoratedMatcher = matcher;
            matcher = (currentPlayer, target) -> {
                boolean result = decoratedMatcher.matches(currentPlayer, target);
                diagnostics.recordMatcherResult(target, result);
                return result;
            };
        }

        return new ChainPlanningRuntime(searchContext, resolverContext, candidateFilter, traverser, matcher);
    }

    private static ChainSearchContext createSearchContext(
        World world,
        BlockSeedSnapshot seedSnapshot,
        ChainSubMode subMode,
        int maxRadius,
        int maxTargets,
        ConcurrentLinkedQueue<ChainTarget> currentFrontier,
        club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot modeExtension) {
        ConcurrentLinkedQueue<ChainTarget> nextFrontier = new ConcurrentLinkedQueue<ChainTarget>();
        Set<ChainTarget> visited = ConcurrentHashMap.newKeySet();
        return new ChainSearchContext(
            world,
            seedSnapshot.getOrigin(),
            seedSnapshot.getSampleBlock(),
            seedSnapshot.getSampleMeta(),
            seedSnapshot.getSampleTileEntity(),
            subMode,
            maxRadius,
            maxTargets,
            currentFrontier,
            nextFrontier,
            visited, modeExtension);
    }

    private static ChainCandidateFilter createCandidateFilter(final ChainSearchContext context,
            final PlanningDiagnostics diagnostics) {
        ChainCandidateFilter fallback = target -> {
            if (context == null || target == null) {
                return false;
            }

            Block block = context.getWorld().getBlock(target.getX(), target.getY(), target.getZ());
            if (diagnostics != null) {
                diagnostics.captureCandidateBlock(target, block, -1);
            }
            if (block == null || block == Blocks.air) {
                return false;
            }

            ChainSubMode subMode = context.getSubMode();
            if (subMode == ChainSubMode.INTERACT_CROP) {
                TileEntity tileEntity = context.getWorld().getTileEntity(target.getX(), target.getY(), target.getZ());
                return ChainCropRules.isCropBlock(block, tileEntity);
            }

            if (subMode != null && subMode.requiresOreMatch()) {
                TileEntity tileEntity = context.getWorld().getTileEntity(target.getX(), target.getY(), target.getZ());
                return ChainOreRules.isOreBlock(block, tileEntity);
            }

            if (subMode != null && subMode.requiresLogMatch()) {
                int meta = context.getWorld().getBlockMetadata(target.getX(), target.getY(), target.getZ());
                if (diagnostics != null) {
                    diagnostics.captureCandidateBlock(target, block, meta);
                }
                return ChainLogRules.isLogBlock(context.getWorld(), target.getX(), target.getY(), target.getZ(), block, meta);
            }

            if (subMode != null && subMode.requiresSameBlockMatch()) {
                return ChainBlockIdentity.matches(
                    context.getWorld(),
                    context.getSampleBlock(),
                    context.getSampleMeta(),
                    context.getSampleTileEntity(),
                    target,
                    diagnostics);
            }

            return true;
        };
        final ChainCandidateFilter base = ChainSubModeRegistry.createCandidateFilter(context, fallback);
        final ChainCandidateFilter decorated = ModeExtensionMatcherDecorator.decorateCandidateFilter(
            context.getSubMode(), base, context.getFrozenModePredicate(), context.getWorld());
        if (diagnostics == null || decorated == null) {
            return decorated;
        }
        return target -> {
            diagnostics.beginCandidate(target);
            boolean result = decorated.canTraverse(target);
            diagnostics.recordCandidateResult(target, result);
            return result;
        };
    }

    /** 单行文本诊断输出边界，便于纯 JVM 验证预算与格式。 */
    public interface DiagnosticSink {
        void log(String message);
    }

    /**
     * 单个 planning round 的并发安全有界诊断器。
     *
     * <p>只保存坐标和字符串/primitive 快照；候选预算在最终 decorated filter 调用前领取，
     * 超预算候选只累计计数。所有业务 predicate 仍只调用一次。</p>
     */
    public static final class PlanningDiagnostics {

        private final UUID playerUUID;
        private final long serverRoundId;
        private final int generation;
        private final String mode;
        private final String subMode;
        private final int detailBudget;
        private final DiagnosticSink sink;
        private final AtomicInteger candidateCount = new AtomicInteger();
        private final AtomicInteger detailCount = new AtomicInteger();
        private final AtomicInteger suppressedCount = new AtomicInteger();
        private final ConcurrentHashMap<ChainTarget, CandidateObservation> observations =
                new ConcurrentHashMap<ChainTarget, CandidateObservation>();

        /** 创建生产诊断器；INFO 用于保证默认实机日志可见，明细由 round 预算限制。 */
        public static PlanningDiagnostics production(UUID playerUUID, long serverRoundId, int generation,
                String mode, String subMode) {
            return new PlanningDiagnostics(playerUUID, serverRoundId, generation, mode, subMode,
                    DEFAULT_DIAGNOSTIC_CANDIDATE_BUDGET, new DiagnosticSink() {
                        @Override
                        public void log(String message) {
                            MyMod.LOG.info(message);
                        }
                    });
        }

        /** 包级构造供纯 JVM 测试注入 sink 与预算。 */
        PlanningDiagnostics(UUID playerUUID, long serverRoundId, int generation, String mode, String subMode,
                int detailBudget, DiagnosticSink sink) {
            if (playerUUID == null || detailBudget < 0 || sink == null) {
                throw new IllegalArgumentException("diagnostic identity, budget and sink must be valid");
            }
            this.playerUUID = playerUUID;
            this.serverRoundId = serverRoundId;
            this.generation = generation;
            this.mode = String.valueOf(mode);
            this.subMode = String.valueOf(subMode);
            this.detailBudget = detailBudget;
            this.sink = sink;
        }

        /** 在最终 decorated candidate filter 调用前领取一次候选预算。 */
        void beginCandidate(ChainTarget target) {
            candidateCount.incrementAndGet();
            if (target == null || !claimDetail()) {
                suppressedCount.incrementAndGet();
                return;
            }
            observations.put(target, new CandidateObservation(target));
        }

        /** 复用 candidate filter 已读取的 block/meta，不做第二次世界读取。 */
        void captureCandidateBlock(ChainTarget target, Block block, int meta) {
            CandidateObservation observation = observations.get(target);
            if (observation != null) {
                observation.blockRegistry = registryName(block);
                observation.blockMeta = meta;
            }
        }

        /** @return 当前候选是否已领取明细预算。 */
        boolean isTracking(ChainTarget target) {
            return target != null && observations.containsKey(target);
        }

        /** 记录最终 decorated candidate filter 结果。 */
        void recordCandidateResult(ChainTarget target, boolean result) {
            CandidateObservation observation = observations.get(target);
            if (observation == null) {
                return;
            }
            observation.candidateResult = result;
            if (!result) {
                observation.reason = "candidate-filter";
                emitAndRemove(target, observation);
            }
        }

        /** 记录 ChainHarvestRules 单次判定中已读取的纯值事实。 */
        void recordHarvestResult(ChainTarget target, Block block, int meta, String toolSummary,
                String durabilityResult, String canHarvestResult, String reason) {
            CandidateObservation observation = observations.get(target);
            if (observation == null) {
                return;
            }
            observation.blockRegistry = registryName(block);
            observation.blockMeta = meta;
            observation.toolSummary = safe(toolSummary);
            observation.durabilityResult = safe(durabilityResult);
            observation.canHarvestResult = safe(canHarvestResult);
            observation.harvestReason = safe(reason);
        }

        /** 记录最终 decorated matcher 结果并输出一条完整候选明细。 */
        void recordMatcherResult(ChainTarget target, boolean result) {
            CandidateObservation observation = observations.get(target);
            if (observation == null) {
                return;
            }
            observation.matcherResult = String.valueOf(result);
            observation.reason = result ? "accepted"
                    : (observation.harvestReason.equals("not-run") ? "matcher-rejected" : observation.harvestReason);
            emitAndRemove(target, observation);
        }

        /** 输出 PlanStarted 主线程纯值上下文。 */
        public void logPlanStarted(String seedRegistry, int seedMeta, ChainTarget origin, String threadName,
                int selectedSlot, String toolSummary) {
            log("stage=PlanStarted read=main-seed seed=" + safe(seedRegistry) + "@" + seedMeta
                    + " origin=" + coordinates(origin) + " thread=" + safe(threadName)
                    + " selected=" + selectedSlot + " tool=[" + safe(toolSummary) + "]");
        }

        /** 输出一次 worker 首次真实采掘判定线程。 */
        void logWorkerReadOnce(String threadName) {
            // 利用特殊 observation key 不安全；原子 CAS 以 detailCount 外独立字段实现。
            if (workerReadLogged.compareAndSet(false, true)) {
                log("stage=WorkerRead read=worker thread=" + safe(threadName));
            }
        }

        private final java.util.concurrent.atomic.AtomicBoolean workerReadLogged =
                new java.util.concurrent.atomic.AtomicBoolean();

        /** 输出 round 规划汇总。 */
        public void logPlanCompleted(int confirmedCount) {
            log("stage=PlanCompleted workerConfirmed=" + confirmedCount
                    + " candidates=" + candidateCount.get()
                    + " details=" + detailCount.get()
                    + " suppressed=" + suppressedCount.get()
                    + " pendingDetails=" + observations.size());
        }

        /** 输出取消汇总，不改写原业务 reason。 */
        public void logPlanCancelled(String reason) {
            log("stage=PlanCancelled reason=" + safe(reason)
                    + " candidates=" + candidateCount.get()
                    + " details=" + detailCount.get()
                    + " suppressed=" + suppressedCount.get());
        }

        public int getCandidateCount() {
            return candidateCount.get();
        }

        public int getDetailCount() {
            return detailCount.get();
        }

        public int getSuppressedCount() {
            return suppressedCount.get();
        }

        private boolean claimDetail() {
            while (true) {
                int current = detailCount.get();
                if (current >= detailBudget) {
                    return false;
                }
                if (detailCount.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        private void emitAndRemove(ChainTarget target, CandidateObservation observation) {
            if (!observations.remove(target, observation)) {
                return;
            }
            log("stage=Candidate pos=(" + observation.x + "," + observation.y + "," + observation.z + ")"
                    + " block=" + observation.blockRegistry + "@" + observation.blockMeta
                    + " canTraverse=" + observation.candidateResult
                    + " matcher=" + observation.matcherResult
                    + " durability=" + observation.durabilityResult
                    + " canHarvestBlock=" + observation.canHarvestResult
                    + " harvestReason=" + observation.harvestReason
                    + " resultReason=" + observation.reason
                    + " tool=[" + observation.toolSummary + "]");
        }

        private void log(String payload) {
            try {
                sink.log("[ChainPlanDiag] player=" + playerUUID
                        + " round=" + serverRoundId
                        + " generation=" + generation
                        + " mode=" + mode
                        + " subMode=" + subMode
                        + " " + payload);
            } catch (RuntimeException ignored) {
                // 诊断 sink 与业务 predicate 隔离。
            } catch (LinkageError ignored) {
                // 日志实现缺失不能改变规划结果。
            }
        }

        private static String registryName(Block block) {
            if (block == null) {
                return "null";
            }
            Object name = Block.blockRegistry.getNameForObject(block);
            return name == null ? "minecraft:unknown" : String.valueOf(name);
        }

        private static String coordinates(ChainTarget target) {
            return target == null ? "unavailable"
                    : "(" + target.getX() + "," + target.getY() + "," + target.getZ() + ")";
        }

        private static String safe(String value) {
            return value == null ? "unavailable" : value.replace('\n', '_').replace('\r', '_');
        }
    }

    /** 一条候选明细的 primitive/string 聚合，不持有 World/Player/Block。 */
    private static final class CandidateObservation {

        private final int x;
        private final int y;
        private final int z;
        private String blockRegistry = "unavailable-without-reread";
        private int blockMeta = -1;
        private boolean candidateResult;
        private String matcherResult = "not-run";
        private String durabilityResult = "not-run";
        private String canHarvestResult = "not-run";
        private String harvestReason = "not-run";
        private String reason = "not-run";
        private String toolSummary = "not-read";

        private CandidateObservation(ChainTarget target) {
            this.x = target.getX();
            this.y = target.getY();
            this.z = target.getZ();
        }
    }
}
