package club.heiqi.qz_miner.toolswap.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.planner.ChainHarvestRules;
import club.heiqi.qz_miner.network.PacketAutoToolSwapTakeoverRequest;
import club.heiqi.qz_miner.toolswap.AutoToolUsabilityPolicy;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapTakeoverRequest;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;

/** 服务端主线程上的 poll 前工具接替协调门。 */
public final class AutoToolSwapTakeoverCoordinator {

    public enum GateResult { PROCEED, WAIT, SKIP_TARGET, STOP }

    /** 服务端当前真实玩家与目标的最终采掘权威。 */
    interface HarvestAuthority {
        boolean canHarvest();
    }

    private final AutoToolSwapRoundService roundService;
    private final RequestSender requestSender;
    private final Map<UUID, IssuedTakeover> issued = new HashMap<UUID, IssuedTakeover>();

    /** 使用生产网络发送器构造。 */
    public AutoToolSwapTakeoverCoordinator(AutoToolSwapRoundService roundService) {
        this(roundService, new RequestSender() {
            @Override
            public void send(Object endpoint, AutoToolSwapTakeoverRequest request) {
                if (MyMod.networkMain != null && endpoint instanceof EntityPlayerMP) {
                    MyMod.networkMain.network.sendTo(new PacketAutoToolSwapTakeoverRequest(request),
                            (EntityPlayerMP) endpoint);
                }
            }
        });
    }

    /** 注入发送边界，确保纯 JVM 测试不加载网络运行态。 */
    AutoToolSwapTakeoverCoordinator(AutoToolSwapRoundService roundService, RequestSender requestSender) {
        if (roundService == null || requestSender == null) {
            throw new IllegalArgumentException("coordinator dependencies must not be null");
        }
        this.roundService = roundService;
        this.requestSender = requestSender;
    }

    /** 从真实世界与玩家库存建立 poll 前门。 */
    public GateResult beforePoll(EntityPlayerMP player, long serverRoundId, int generation,
            ChainTarget target, long serverTick) {
        if (player == null || target == null || player.worldObj == null) return GateResult.STOP;
        Block block = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
        int blockId = block == null ? 0 : Block.getIdFromBlock(block);
        int metadata = player.worldObj.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        return beforePoll(player.getUniqueID(), player, serverRoundId, generation,
                target.getX(), target.getY(), target.getZ(), blockId, metadata,
                new MinecraftAutoToolSwapInventoryPort(player), serverTick,
                Math.max(1, Math.min(10, Config.chainWatchdogTimeoutTicks - 1)),
                new HarvestAuthority() {
                    @Override
                    public boolean canHarvest() {
                        return ChainHarvestRules.canHarvest(player, target);
                    }
                });
    }

    /** 纯逻辑协调入口；所有调用都必须位于服务端主线程。 */
    GateResult beforePoll(UUID playerId, Object endpoint, long serverRoundId, int generation,
            int targetX, int targetY, int targetZ, int blockId, int metadata,
            AutoToolSwapInventoryPort inventory, long serverTick, int timeoutTicks) {
        return beforePoll(playerId, endpoint, serverRoundId, generation, targetX, targetY, targetZ,
                blockId, metadata, inventory, serverTick, timeoutTicks, new HarvestAuthority() {
                    @Override
                    public boolean canHarvest() {
                        return true;
                    }
                });
    }

    /** 纯逻辑协调入口，可注入执行权威以覆盖复验和异常 fail-closed。 */
    GateResult beforePoll(UUID playerId, Object endpoint, long serverRoundId, int generation,
            int targetX, int targetY, int targetZ, int blockId, int metadata,
            AutoToolSwapInventoryPort inventory, long serverTick, int timeoutTicks,
            HarvestAuthority authority) {
        if (authority == null) return GateResult.STOP;
        if (playerId == null || endpoint == null || inventory == null || timeoutTicks <= 0) return GateResult.STOP;
        IssuedTakeover active = issued.get(playerId);
        if (serverRoundId == 0L) {
            // round 0 不得继承旧 round 的本地发送记忆；精确退休后仍只查询当前目标权威。
            if (active != null) stopAndConsumeIssued(playerId, active);
            return evaluateTargetAuthority(authority);
        }
        if (active != null) {
            AutoToolSwapTakeoverRequest activeRequest = active.request;
            if (!active.matchesEndpoint(endpoint) || !active.matchesExecutionIdentity(serverRoundId, generation,
                    targetX, targetY, targetZ)) {
                stopAndConsumeIssued(playerId, active);
                return GateResult.STOP;
            }
            AutoToolSwapRoundService.TakeoverGateState state = roundService.takeoverGateState(
                    playerId, endpoint, activeRequest, serverTick);
            if (!active.matchesTargetBlock(blockId, metadata)) {
                if (state == AutoToolSwapRoundService.TakeoverGateState.WAITING) {
                    // 未结算等待门没有退休 sequence，目标漂移必须停止会话，避免迟到 intent 串门。
                    stopAndConsumeIssued(playerId, active);
                    return GateResult.STOP;
                }
                roundService.consumeTakeoverGate(playerId, endpoint, activeRequest);
                issued.remove(playerId);
                return isSafelySettledTargetGate(state) ? GateResult.SKIP_TARGET : GateResult.STOP;
            }
            if (state == AutoToolSwapRoundService.TakeoverGateState.WAITING) return GateResult.WAIT;
            roundService.consumeTakeoverGate(playerId, endpoint, activeRequest);
            issued.remove(playerId);
            if (state == AutoToolSwapRoundService.TakeoverGateState.APPLIED) {
                return evaluateTargetAuthority(authority);
            }
            if (state == AutoToolSwapRoundService.TakeoverGateState.DECLINED) {
                AutoToolSwapRoundService.TargetCapabilityKey targetCapability;
                try {
                    targetCapability = AutoToolSwapRoundService.TargetCapabilityKey.of(blockId, metadata);
                } catch (IllegalArgumentException invalidTarget) {
                    return GateResult.SKIP_TARGET;
                }
                return installEmptyHandFallbackLease(playerId, endpoint, serverRoundId, generation,
                        targetCapability, inventory, active.anchorSlot, authority);
            }
            if (state == AutoToolSwapRoundService.TakeoverGateState.SKIP_TARGET) {
                return GateResult.SKIP_TARGET;
            }
            return GateResult.STOP;
        }

        AutoToolSwapRoundService.TargetCapabilityKey targetCapability;
        try {
            targetCapability = AutoToolSwapRoundService.TargetCapabilityKey.of(blockId, metadata);
        } catch (IllegalArgumentException invalidTarget) {
            roundService.clearEmptyHandFallbackLease(playerId, "invalid-target");
            return GateResult.SKIP_TARGET;
        }

        AutoToolSwapStackState anchor;
        int anchorSlot;
        try {
            if (!inventory.isPlayerAlive()
                    || !inventory.hasPersonalInventoryWindow0() || !inventory.isCursorEmpty()) {
                roundService.clearEmptyHandFallbackLease(playerId, "inventory-context");
                return GateResult.STOP;
            }
            anchorSlot = inventory.selectedHotbarSlot();
            anchor = inventory.readInventorySlot(anchorSlot);
            if (anchor == null) {
                roundService.clearEmptyHandFallbackLease(playerId, "inventory-read-failed");
                return GateResult.STOP;
            }
            if (inventory.isCreativeMode()) {
                roundService.clearEmptyHandFallbackLease(playerId, "creative-mode");
                return evaluateTargetAuthority(authority);
            }
        } catch (RuntimeException failure) {
            roundService.clearEmptyHandFallbackLease(playerId, "inventory-read-failed");
            return GateResult.STOP;
        } catch (LinkageError failure) {
            roundService.clearEmptyHandFallbackLease(playerId, "inventory-read-failed");
            return GateResult.STOP;
        }
        if (roundService.hasEmptyHandFallbackLease(playerId)) {
            AutoToolSwapRoundService.InventoryFingerprint inventoryFingerprint;
            try {
                inventoryFingerprint = inventory.readInventoryIdentity();
            } catch (RuntimeException failure) {
                roundService.clearEmptyHandFallbackLease(playerId, "inventory-identity-read-failed");
                return GateResult.STOP;
            } catch (LinkageError failure) {
                roundService.clearEmptyHandFallbackLease(playerId, "inventory-identity-read-failed");
                return GateResult.STOP;
            }
            AutoToolSwapRoundService.EmptyHandFallbackLeaseMatchResult leaseMatch = roundService
                    .matchEmptyHandFallbackLease(playerId, endpoint, serverRoundId, generation,
                            targetCapability, anchorSlot, inventoryFingerprint);
            if (leaseMatch.outcome() == AutoToolSwapRoundService.EmptyHandFallbackLeaseMatch.MATCH) {
                AutoToolSwapRoundService.EmptyHandFallbackLeaseToken leaseToken = leaseMatch.token();
                GateResult authorityResult = evaluateTargetAuthority(authority);
                if (authorityResult != GateResult.PROCEED) {
                    // 权威可同线程重入模组代码；只退休调用权威前实际命中的同一租约。
                    roundService.compareAndClearEmptyHandFallbackLease(playerId, leaseToken,
                            "authority-failed");
                }
                return authorityResult;
            }
        }
        if (!anchor.isEmpty()) {
            Boolean currentCanHarvest = queryAuthority(authority);
            if (currentCanHarvest == null) return GateResult.SKIP_TARGET;
            if (currentCanHarvest.booleanValue()
                    && AutoToolUsabilityPolicy.hasDurabilityReserve(anchor.remainingDurability())) {
                return GateResult.PROCEED;
            }
        }
        long deadline = serverTick > Long.MAX_VALUE - timeoutTicks ? Long.MAX_VALUE : serverTick + timeoutTicks;
        AutoToolSwapTakeoverRequest request = roundService.prepareTakeover(playerId, endpoint, serverRoundId,
                generation, targetX, targetY, targetZ, blockId, metadata, anchorSlot, anchor,
                serverTick, deadline);
        if (request == null) return GateResult.STOP;
        IssuedTakeover issuedTakeover = new IssuedTakeover(endpoint, request, anchorSlot);
        issued.put(playerId, issuedTakeover);
        try {
            requestSender.send(endpoint, request);
            return GateResult.WAIT;
        } catch (RuntimeException failure) {
            stopAndConsumeIssued(playerId, issuedTakeover);
            return GateResult.STOP;
        } catch (LinkageError failure) {
            stopAndConsumeIssued(playerId, issuedTakeover);
            return GateResult.STOP;
        }
    }

    /** DECLINED 仅在同一安全空手上下文与实时权威均成立后安装 round-scoped 租约。 */
    private GateResult installEmptyHandFallbackLease(UUID playerId, Object endpoint, long serverRoundId,
            int generation, AutoToolSwapRoundService.TargetCapabilityKey targetCapability,
            AutoToolSwapInventoryPort inventory, int anchorSlot, HarvestAuthority authority) {
        AutoToolSwapRoundService.InventoryFingerprint inventoryFingerprint;
        try {
            if (!inventory.isPlayerAlive() || inventory.isCreativeMode()
                    || !inventory.hasPersonalInventoryWindow0() || !inventory.isCursorEmpty()
                    || inventory.selectedHotbarSlot() != anchorSlot) {
                return GateResult.STOP;
            }
            AutoToolSwapStackState current = inventory.readInventorySlot(anchorSlot);
            if (current == null || !current.isEmpty()) return GateResult.STOP;
            inventoryFingerprint = inventory.readInventoryIdentity();
            if (inventoryFingerprint == null || !inventoryFingerprint.slot(anchorSlot).isEmpty()) {
                return GateResult.STOP;
            }
        } catch (RuntimeException failure) {
            return GateResult.STOP;
        } catch (LinkageError failure) {
            return GateResult.STOP;
        }
        GateResult authorityResult = evaluateTargetAuthority(authority);
        if (authorityResult != GateResult.PROCEED) return authorityResult;
        return roundService.installEmptyHandFallbackLease(playerId, endpoint, serverRoundId, generation,
                targetCapability, anchorSlot, inventoryFingerprint) ? GateResult.PROCEED : GateResult.STOP;
    }

    /** 权威拒绝或异常不得执行当前目标，但不放大为已安全隔离事务之外的会话取消。 */
    private static GateResult evaluateTargetAuthority(HarvestAuthority authority) {
        Boolean result = queryAuthority(authority);
        return Boolean.TRUE.equals(result) ? GateResult.PROCEED : GateResult.SKIP_TARGET;
    }

    /** @return 该终态是否已经安全结算并退休动作 sequence，可局部跳过当前目标。 */
    private static boolean isSafelySettledTargetGate(AutoToolSwapRoundService.TakeoverGateState state) {
        return state == AutoToolSwapRoundService.TakeoverGateState.APPLIED
                || state == AutoToolSwapRoundService.TakeoverGateState.DECLINED
                || state == AutoToolSwapRoundService.TakeoverGateState.SKIP_TARGET;
    }

    /** @return 权威结果；异常返回 null。 */
    private static Boolean queryAuthority(HarvestAuthority authority) {
        try {
            return Boolean.valueOf(authority.canHarvest());
        } catch (RuntimeException failure) {
            return null;
        } catch (LinkageError failure) {
            return null;
        }
    }

    /** 精确停止并消费已经发出的等待门，避免发送失败或目标漂移后迟到意图写库存。 */
    private void stopAndConsumeIssued(UUID playerId, IssuedTakeover active) {
        roundService.stopTakeoverGate(playerId, active.endpoint, active.request);
        roundService.consumeTakeoverGate(playerId, active.endpoint, active.request);
        issued.remove(playerId);
    }

    /** 生命周期清理本地发送记忆；round service 由既有统一入口清理。 */
    public void cleanup(UUID playerId) {
        issued.remove(playerId);
    }

    /** 服务停止时清除全部仅用于抑制重复发包的本地记忆。 */
    public void clearAll() {
        issued.clear();
    }

    interface RequestSender {
        void send(Object endpoint, AutoToolSwapTakeoverRequest request);
    }

    /** 本地发送记忆同时冻结 endpoint identity，防止重连后的新 endpoint 接管旧请求。 */
    private static final class IssuedTakeover {
        private final Object endpoint;
        private final AutoToolSwapTakeoverRequest request;
        private final int anchorSlot;

        private IssuedTakeover(Object endpoint, AutoToolSwapTakeoverRequest request, int anchorSlot) {
            this.endpoint = endpoint;
            this.request = request;
            this.anchorSlot = anchorSlot;
        }

        private boolean matchesEndpoint(Object currentEndpoint) {
            return endpoint == currentEndpoint;
        }

        /** @return round、generation 与队首坐标是否仍属于同一执行身份。 */
        private boolean matchesExecutionIdentity(long serverRoundId, int generation,
                int targetX, int targetY, int targetZ) {
            return request.serverRoundId() == serverRoundId && request.generation() == generation
                    && request.targetX() == targetX && request.targetY() == targetY
                    && request.targetZ() == targetZ;
        }

        /** @return 当前 block/meta 是否仍与请求冻结事实一致。 */
        private boolean matchesTargetBlock(int blockId, int metadata) {
            return request.targetBlockId() == blockId && request.targetBlockMetadata() == metadata;
        }
    }
}
