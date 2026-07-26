package club.heiqi.qz_miner.chain.executor;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.AxisAlignedBB;

/**
 * 服务端玩家虚拟射线姿态的可回滚事务。
 */
final class ServerPlayerPoseTransaction implements AutoCloseable {

    private final EntityPlayerMP player;
    private final double originalPosX;
    private final double originalPosY;
    private final double originalPosZ;
    private final double originalPrevPosX;
    private final double originalPrevPosY;
    private final double originalPrevPosZ;
    private final double originalLastTickPosX;
    private final double originalLastTickPosY;
    private final double originalLastTickPosZ;
    private final float originalRotationYaw;
    private final float originalRotationPitch;
    private final float originalPrevRotationYaw;
    private final float originalPrevRotationPitch;
    private final AxisAlignedBB originalBoundingBox;
    private final double originalMinX;
    private final double originalMinY;
    private final double originalMinZ;
    private final double originalMaxX;
    private final double originalMaxY;
    private final double originalMaxZ;

    private boolean applied;
    private boolean closed;

    private ServerPlayerPoseTransaction(EntityPlayerMP player) {
        this.player = player;
        this.originalPosX = player.posX;
        this.originalPosY = player.posY;
        this.originalPosZ = player.posZ;
        this.originalPrevPosX = player.prevPosX;
        this.originalPrevPosY = player.prevPosY;
        this.originalPrevPosZ = player.prevPosZ;
        this.originalLastTickPosX = player.lastTickPosX;
        this.originalLastTickPosY = player.lastTickPosY;
        this.originalLastTickPosZ = player.lastTickPosZ;
        this.originalRotationYaw = player.rotationYaw;
        this.originalRotationPitch = player.rotationPitch;
        this.originalPrevRotationYaw = player.prevRotationYaw;
        this.originalPrevRotationPitch = player.prevRotationPitch;
        this.originalBoundingBox = player.boundingBox;
        this.originalMinX = originalBoundingBox.minX;
        this.originalMinY = originalBoundingBox.minY;
        this.originalMinZ = originalBoundingBox.minZ;
        this.originalMaxX = originalBoundingBox.maxX;
        this.originalMaxY = originalBoundingBox.maxY;
        this.originalMaxZ = originalBoundingBox.maxZ;
    }

    /**
     * 捕获玩家全部待改字段及碰撞盒精确坐标，尚不应用虚拟姿态。
     *
     * @param player 当前服务端玩家
     * @return 可应用一次并可重复关闭的事务
     */
    static ServerPlayerPoseTransaction capture(EntityPlayerMP player) {
        if (player == null || player.boundingBox == null) {
            throw new IllegalArgumentException("player and boundingBox must be non-null");
        }
        return new ServerPlayerPoseTransaction(player);
    }

    /**
     * 将 current/previous/last-tick 坐标与 current/previous 旋转投影到同一虚拟姿态。
     *
     * @param pose 目标纯值 eye pose
     */
    void apply(TargetInteractionPose pose) {
        if (pose == null) {
            throw new IllegalArgumentException("pose must be non-null");
        }
        if (closed || applied) {
            throw new IllegalStateException("pose transaction is no longer applicable");
        }

        double virtualPosX = pose.getEyeX();
        double virtualPosY = pose.getEyeY() - player.getEyeHeight();
        double virtualPosZ = pose.getEyeZ();
        if (!isFinite(virtualPosX) || !isFinite(virtualPosY) || !isFinite(virtualPosZ)
                || !isFinite(pose.getYaw()) || !isFinite(pose.getPitch())) {
            throw new IllegalArgumentException("virtual pose must be finite");
        }

        double deltaX = virtualPosX - originalPosX;
        double deltaY = virtualPosY - originalPosY;
        double deltaZ = virtualPosZ - originalPosZ;
        player.posX = virtualPosX;
        player.posY = virtualPosY;
        player.posZ = virtualPosZ;
        player.prevPosX = virtualPosX;
        player.prevPosY = virtualPosY;
        player.prevPosZ = virtualPosZ;
        player.lastTickPosX = virtualPosX;
        player.lastTickPosY = virtualPosY;
        player.lastTickPosZ = virtualPosZ;
        player.rotationYaw = pose.getYaw();
        player.rotationPitch = pose.getPitch();
        player.prevRotationYaw = pose.getYaw();
        player.prevRotationPitch = pose.getPitch();
        originalBoundingBox.setBounds(
                originalMinX + deltaX,
                originalMinY + deltaY,
                originalMinZ + deltaZ,
                originalMaxX + deltaX,
                originalMaxY + deltaY,
                originalMaxZ + deltaZ);
        applied = true;
    }

    /**
     * 尽最大努力恢复全部已捕获字段；重复调用不再改写玩家。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Throwable restoreFailure = null;

        try {
            player.posX = originalPosX;
            player.posY = originalPosY;
            player.posZ = originalPosZ;
        } catch (RuntimeException | LinkageError failure) {
            restoreFailure = mergeFailure(restoreFailure, failure);
        }
        try {
            player.prevPosX = originalPrevPosX;
            player.prevPosY = originalPrevPosY;
            player.prevPosZ = originalPrevPosZ;
        } catch (RuntimeException | LinkageError failure) {
            restoreFailure = mergeFailure(restoreFailure, failure);
        }
        try {
            player.lastTickPosX = originalLastTickPosX;
            player.lastTickPosY = originalLastTickPosY;
            player.lastTickPosZ = originalLastTickPosZ;
        } catch (RuntimeException | LinkageError failure) {
            restoreFailure = mergeFailure(restoreFailure, failure);
        }
        try {
            player.rotationYaw = originalRotationYaw;
            player.rotationPitch = originalRotationPitch;
            player.prevRotationYaw = originalPrevRotationYaw;
            player.prevRotationPitch = originalPrevRotationPitch;
        } catch (RuntimeException | LinkageError failure) {
            restoreFailure = mergeFailure(restoreFailure, failure);
        }
        try {
            originalBoundingBox.setBounds(
                    originalMinX, originalMinY, originalMinZ,
                    originalMaxX, originalMaxY, originalMaxZ);
        } catch (RuntimeException | LinkageError failure) {
            restoreFailure = mergeFailure(restoreFailure, failure);
        }

        if (restoreFailure instanceof RuntimeException) {
            throw (RuntimeException) restoreFailure;
        }
        if (restoreFailure instanceof LinkageError) {
            throw (LinkageError) restoreFailure;
        }
    }

    private static Throwable mergeFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
