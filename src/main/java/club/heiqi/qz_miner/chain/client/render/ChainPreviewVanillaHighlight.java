package club.heiqi.qz_miner.chain.client.render;

/**
 * B2.5 原版方块高亮协同纯决策（无 GL、无状态、不依赖 Minecraft 类型）。
 *
 * <p>三条件同时满足才抑制原版黑色选择框：开关开启 + 预览激活 + 瞄准方块属于当前预览代
 * （坐标与 {@code ChainPreviewState.getOrigin()} 一致）。任一条件不满足一律返回 false
 * （fail-open：宁可多画原版框，也不误抑制）；开关关闭时第一项即短路，默认档逐字等于今天。</p>
 *
 * <p>匹配基准使用 controller 的预览代 origin（播种目标），不是 draw plan 的 origin——
 * B4.1 后 plan origin 是代内稳定锚点，可能与瞄准方块不同。</p>
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
