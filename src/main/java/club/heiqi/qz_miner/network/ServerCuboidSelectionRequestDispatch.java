package club.heiqi.qz_miner.network;

import java.lang.ref.WeakReference;
import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.interaction.InteractionRayTrace;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.selection.CuboidSelection;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.network.ServerChainConfigRequestDispatch.EndpointKey;
import club.heiqi.qz_miner.thread.KeyedLatestTaskLane;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MovingObjectPosition;

/** 将选点请求按 endpoint/point latest-wins 转交服务端主线程并做权威校验。 */
public final class ServerCuboidSelectionRequestDispatch {

    private ServerCuboidSelectionRequestDispatch() {}

    public static boolean submit(final EntityPlayerMP player, final int protocolVersion,
            final int pointIndex, final int x, final int y, final int z, final boolean rawValid) {
        if (player == null) return false;
        final UUID playerId = player.getUniqueID();
        final WeakReference<EntityPlayerMP> endpoint = new WeakReference<EntityPlayerMP>(player);
        int keyPointIndex = pointIndex == 1 || pointIndex == 2 ? pointIndex : 0;
        boolean accepted = ServerMainThreadDispatcher.tryRunLatest(
                new SelectionKey(EndpointKey.of(player), keyPointIndex), new Runnable() {
            @Override
            public void run() {
                consume(playerId, endpoint, protocolVersion, pointIndex, x, y, z, rawValid);
            }
        });
        if (!accepted) sendRejectedSnapshot(player, "dispatch-rejected");
        return accepted;
    }

    private static void consume(UUID playerId, WeakReference<EntityPlayerMP> endpoint,
            int protocolVersion, int pointIndex, int x, int y, int z, boolean rawValid) {
        EntityPlayerMP captured = endpoint.get();
        EntityPlayer current = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerId);
        if (captured == null || current != captured || captured.worldObj == null
                || MyMod.chainStateService == null) {
            return;
        }
        ChainPlayerState state = MyMod.chainStateService.getPlayerState(playerId);
        if (state == null) return;
        if (!rawValid || protocolVersion != PacketCuboidSelectionRequest.PROTOCOL_VERSION
                || (pointIndex != 1 && pointIndex != 2)
                || y < 0 || y >= captured.worldObj.getHeight()) {
            sendSync(captured, state.getCuboidSelection(), false, "invalid-request");
            return;
        }
        if (state.getSelectedMode() != ChainMode.AREA
                || state.getSelectedSubMode() != ChainSubMode.AREA_CUBOID_CLEAR
                || state.isChainKeyPressed() || !isCurrentRayTarget(captured, x, y, z)) {
            sendSync(captured, state.getCuboidSelection(), false, "authority-rejected");
            return;
        }
        CuboidSelection.Update update = state.selectCuboidPoint(
                pointIndex, captured.dimension, x, y, z);
        sendSync(captured, update.getSelection(), update.isAccepted(), update.getReason());
    }

    private static void sendSync(EntityPlayerMP player, CuboidSelection selection, boolean accepted, String reason) {
        if (MyMod.networkMain != null) {
            MyMod.networkMain.network.sendTo(new PacketCuboidSelectionSync(selection, accepted, reason), player);
        }
    }

    /** lane 关闭或容量拒绝时只读 immutable volatile 快照，不在 Netty 线程修改语义状态。 */
    private static void sendRejectedSnapshot(EntityPlayerMP player, String reason) {
        if (MyMod.chainStateService == null) return;
        ChainPlayerState state = MyMod.chainStateService.getPlayerState(player.getUniqueID());
        if (state != null) sendSync(player, state.getCuboidSelection(), false, reason);
    }

    private static boolean isCurrentRayTarget(EntityPlayerMP player, int x, int y, int z) {
        if (player.theItemInWorldManager == null || player.worldObj == null) return false;
        try {
            MovingObjectPosition hit = InteractionRayTrace.trace(
                    player, player.theItemInWorldManager.getBlockReachDistance(), false);
            return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && hit.blockX == x && hit.blockY == y && hit.blockZ == z;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** 每个端点最多占 point1、point2 与非法请求三个有界槽。 */
    static final class SelectionKey implements KeyedLatestTaskLane.StaleDetectableKey {
        private final EndpointKey endpoint;
        private final int pointIndex;

        SelectionKey(EndpointKey endpoint, int pointIndex) {
            this.endpoint = endpoint;
            this.pointIndex = pointIndex;
        }

        @Override
        public boolean isStale() { return endpoint.isStale(); }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SelectionKey)) return false;
            SelectionKey that = (SelectionKey) other;
            return pointIndex == that.pointIndex && endpoint.equals(that.endpoint);
        }

        @Override
        public int hashCode() { return 31 * endpoint.hashCode() + pointIndex; }
    }
}
