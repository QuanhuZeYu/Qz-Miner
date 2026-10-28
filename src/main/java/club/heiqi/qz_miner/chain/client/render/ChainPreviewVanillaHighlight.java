package club.heiqi.qz_miner.chain.client.render;

/**
 * 开关、预览激活与播种 origin 匹配同时成立时抑制原版选择框。
 * 缺少任一条件保留原版绘制；几何稳定锚点另作换基，比较始终使用预览播种坐标。
 */
public final class ChainPreviewVanillaHighlight {

    private ChainPreviewVanillaHighlight() {
    }

    /**
     * 纯函数：是否抑制原版方块选择框（8 组边界，仅 {@code (true, true, true)} 返回 true）。
     *
     * @param suppressConfigured        clientPreviewSuppressVanillaHighlight 开关
     * @param previewActive             预览是否处于激活态
     * @param aimedTargetIsPreviewOrigin 瞄准方块坐标是否与预览代 origin 一致
     * @return 三条件同时满足时为 true
     */
    public static boolean shouldSuppress(
            boolean suppressConfigured,
            boolean previewActive,
            boolean aimedTargetIsPreviewOrigin) {
        return suppressConfigured && previewActive && aimedTargetIsPreviewOrigin;
    }

    /**
     * 纯函数：瞄准方块坐标与预览代 origin 的一致性判定；origin 未建立时不匹配（fail-open）。
     *
     * @param hasOrigin 当前预览代是否存在 origin
     * @param originX   预览代 origin X
     * @param originY   预览代 origin Y
     * @param originZ   预览代 origin Z
     * @param aimedX    瞄准方块 X
     * @param aimedY    瞄准方块 Y
     * @param aimedZ    瞄准方块 Z
     * @return 存在 origin 且三轴全等时为 true
     */
    public static boolean matchesOrigin(
            boolean hasOrigin,
            int originX,
            int originY,
            int originZ,
            int aimedX,
            int aimedY,
            int aimedZ) {
        if (!hasOrigin) {
            return false;
        }
        return originX == aimedX && originY == aimedY && originZ == aimedZ;
    }
}
