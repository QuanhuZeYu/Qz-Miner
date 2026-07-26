package club.heiqi.qz_miner.chain.executor;

/**
 * 指向单个目标块中心的纯值虚拟 eye pose。
 */
final class TargetInteractionPose {

    private static final double FACE_INSET = 0.125D;
    private static final double HALF_BLOCK = 0.5D;
    private static final double DEGREES_PER_RADIAN = 180.0D / Math.PI;

    private final double eyeX;
    private final double eyeY;
    private final double eyeZ;
    private final float yaw;
    private final float pitch;
    private final int normalizedFace;

    private TargetInteractionPose(double eyeX, double eyeY, double eyeZ,
            float yaw, float pitch, int normalizedFace) {
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.yaw = yaw;
        this.pitch = pitch;
        this.normalizedFace = normalizedFace;
    }

    /**
     * 在目标块内部、靠近指定面的点建立朝向块中心的姿态。
     *
     * @param targetX 目标 X
     * @param targetY 目标 Y
     * @param targetZ 目标 Z
     * @param face Minecraft 六面编号；非法值归一为上表面 1
     * @return 不持有玩家或世界引用的纯值姿态
     */
    static TargetInteractionPose forTarget(int targetX, int targetY, int targetZ, int face) {
        int normalizedFace = normalizeFace(face);
        double centerX = targetX + HALF_BLOCK;
        double centerY = targetY + HALF_BLOCK;
        double centerZ = targetZ + HALF_BLOCK;
        double eyeX = centerX;
        double eyeY = centerY;
        double eyeZ = centerZ;

        switch (normalizedFace) {
            case 0:
                eyeY = targetY + FACE_INSET;
                break;
            case 1:
                eyeY = targetY + 1.0D - FACE_INSET;
                break;
            case 2:
                eyeZ = targetZ + FACE_INSET;
                break;
            case 3:
                eyeZ = targetZ + 1.0D - FACE_INSET;
                break;
            case 4:
                eyeX = targetX + FACE_INSET;
                break;
            case 5:
                eyeX = targetX + 1.0D - FACE_INSET;
                break;
            default:
                throw new IllegalStateException("normalized face must be in 0..5");
        }

        double deltaX = centerX - eyeX;
        double deltaY = centerY - eyeY;
        double deltaZ = centerZ - eyeZ;
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        float yaw = (float) (Math.atan2(-deltaX, deltaZ) * DEGREES_PER_RADIAN);
        float pitch = (float) (-Math.atan2(deltaY, horizontalDistance) * DEGREES_PER_RADIAN);
        if (!isFinite(yaw) || !isFinite(pitch)) {
            yaw = 0.0F;
            pitch = 0.0F;
        }
        return new TargetInteractionPose(eyeX, eyeY, eyeZ, yaw, pitch, normalizedFace);
    }

    double getEyeX() {
        return eyeX;
    }

    double getEyeY() {
        return eyeY;
    }

    double getEyeZ() {
        return eyeZ;
    }

    float getYaw() {
        return yaw;
    }

    float getPitch() {
        return pitch;
    }

    int getNormalizedFace() {
        return normalizedFace;
    }

    private static int normalizeFace(int face) {
        return face >= 0 && face <= 5 ? face : 1;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
