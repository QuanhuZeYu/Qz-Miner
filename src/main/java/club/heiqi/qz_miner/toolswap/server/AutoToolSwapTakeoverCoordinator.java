package club.heiqi.qz_miner.toolswap.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.network.PacketAutoToolSwapTakeoverRequest;
import club.heiqi.qz_miner.toolswap.AutoToolUsabilityPolicy;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapTakeoverRequest;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;

/** 服务端主线程上的 poll 前工具接替协调门。 */
public final class AutoToolSwapTakeoverCoordinator {

    public enum GateResult { PROCEED, WAIT, STOP }

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
                Math.max(1, Math.min(10, Config.chainWatchdogTimeoutTicks - 1)));
    }

    /** 纯逻辑协调入口；所有调用都必须位于服务端主线程。 */
    GateResult beforePoll(UUID playerId, Object endpoint, long serverRoundId, int generation,
            int targetX, int targetY, int targetZ, int blockId, int metadata,
            AutoToolSwapInventoryPort inventory, long serverTick, int timeoutTicks) {
        if (playerId == null || endpoint == null || inventory == null || timeoutTicks <= 0) return GateResult.STOP;
        IssuedTakeover active = issued.get(playerId);
        if (active != null) {
            AutoToolSwapTakeoverRequest activeRequest = active.request;
            if (!active.matchesEndpoint(endpoint) || !activeRequest.matchesTarget(serverRoundId, generation,
                    targetX, targetY, targetZ, blockId, metadata)) {
                stopAndConsumeIssued(playerId, active);
                return GateResult.STOP;
            }
            AutoToolSwapRoundService.TakeoverGateState state = roundService.takeoverGateState(
                    playerId, endpoint, activeRequest, serverTick);
            if (state == AutoToolSwapRoundService.TakeoverGateState.WAITING) return GateResult.WAIT;
            roundService.consumeTakeoverGate(playerId, endpoint, activeRequest);
            issued.remove(playerId);
            return state == AutoToolSwapRoundService.TakeoverGateState.APPLIED
                    ? GateResult.PROCEED : GateResult.STOP;
        }

        AutoToolSwapStackState anchor;
        int anchorSlot;
        try {
            if (!inventory.isPlayerAlive() || inventory.isCreativeMode()
                    || !inventory.hasPersonalInventoryWindow0() || !inventory.isCursorEmpty()) {
                return GateResult.STOP;
            }
            anchorSlot = inventory.selectedHotbarSlot();
            anchor = inventory.readInventorySlot(anchorSlot);
        } catch (RuntimeException failure) {
            return GateResult.STOP;
        } catch (LinkageError failure) {
            return GateResult.STOP;
        }
        if (anchor != null && !anchor.isEmpty()
                && AutoToolUsabilityPolicy.hasDurabilityReserve(anchor.remainingDurability())) {
            return GateResult.PROCEED;
        }
        long deadline = serverTick > Long.MAX_VALUE - timeoutTicks ? Long.MAX_VALUE : serverTick + timeoutTicks;
        AutoToolSwapTakeoverRequest request = roundService.prepareTakeover(playerId, endpoint, serverRoundId,
                generation, targetX, targetY, targetZ, blockId, metadata, anchorSlot, anchor,
                serverTick, deadline);
        if (request == null) return GateResult.STOP;
        IssuedTakeover issuedTakeover = new IssuedTakeover(endpoint, request);
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

        private IssuedTakeover(Object endpoint, AutoToolSwapTakeoverRequest request) {
            this.endpoint = endpoint;
            this.request = request;
        }

        private boolean matchesEndpoint(Object currentEndpoint) {
            return endpoint == currentEndpoint;
        }
    }
}
