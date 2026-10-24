package club.heiqi.qz_miner.chain.client.render;

import org.lwjgl.opengl.GL11;

/**
 * 预览分层的深度与透明度配方（纯数据，本类不调用 GL）。
 *
 * <p>XRAY 恒可见，OCCLUDE 仅显示可见部分，OUTLINE 先画外扩壳再画主体。
 * DEPTH_OPAQUE 先用 GREATER 画遮挡部分，再用 LEQUAL 画可见部分；仅可见段关闭距离透明度，
 * 生长与淡入淡出仍由后端消费同一份计划。所有阶段均不写深度，不改变拓扑或场景深度。</p>
 */
public final class ChainPreviewDepthPass {

    /** 深度分层档位。 */
    public enum Pass {
        XRAY,
        OCCLUDE,
        OUTLINE,
        DEPTH_OPAQUE
    }

    /** 单次绘制的深度与距离透明度配方；实例常驻复用。 */
    public static final class Stage {

        private final boolean depthTestEnabled;
        private final boolean depthMaskEnabled;
        private final int depthFunc;
        private final boolean distanceFadeEnabled;

        private Stage(boolean depthTestEnabled, boolean depthMaskEnabled, int depthFunc) {
            this(depthTestEnabled, depthMaskEnabled, depthFunc, true);
        }

        private Stage(boolean depthTestEnabled, boolean depthMaskEnabled, int depthFunc,
                boolean distanceFadeEnabled) {
            this.depthTestEnabled = depthTestEnabled;
            this.depthMaskEnabled = depthMaskEnabled;
            this.depthFunc = depthFunc;
            this.distanceFadeEnabled = distanceFadeEnabled;
        }

        public boolean isDepthTestEnabled() {
            return depthTestEnabled;
        }

        public boolean isDepthMaskEnabled() {
            return depthMaskEnabled;
        }

        public int getDepthFunc() {
            return depthFunc;
        }

        /** @return 是否保留距离透明度；关闭时仍保留生长与淡入淡出 */
        public boolean isDistanceFadeEnabled() {
            return distanceFadeEnabled;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Stage)) {
                return false;
            }
            Stage that = (Stage) other;
            return depthTestEnabled == that.depthTestEnabled
                && depthMaskEnabled == that.depthMaskEnabled
                && depthFunc == that.depthFunc
                && distanceFadeEnabled == that.distanceFadeEnabled;
        }

        @Override
        public int hashCode() {
            int result = depthTestEnabled ? 1 : 0;
            result = 31 * result + (depthMaskEnabled ? 1 : 0);
            result = 31 * result + depthFunc;
            result = 31 * result + (distanceFadeEnabled ? 1 : 0);
            return result;
        }

        @Override
        public String toString() {
            return "Stage{depthTest=" + depthTestEnabled
                + ", depthMask=" + depthMaskEnabled
                + ", depthFunc=0x" + Integer.toHexString(depthFunc)
                + ", distanceFade=" + distanceFadeEnabled
                + '}';
        }
    }

    /** XRAY：关深测、不写深度，不设置 depthFunc。 */
    public static final Stage XRAY_STAGE = new Stage(false, false, GL11.GL_LEQUAL);

    /** OCCLUDE：开深测、LEQUAL、不写深度。 */
    public static final Stage OCCLUDE_STAGE = new Stage(true, false, GL11.GL_LEQUAL);

    /** OUTLINE 主体段。 */
    public static final Stage OUTLINE_MAIN_STAGE = new Stage(true, false, GL11.GL_LEQUAL);

    /** OUTLINE 外扩壳段：恒可见，先于主体绘制。 */
    public static final Stage OUTLINE_SHELL_STAGE = new Stage(false, false, GL11.GL_LEQUAL);

    /** 历史名称，保留为壳段别名。 */
    public static final Stage OUTLINE_OVERLAY_STAGE = OUTLINE_SHELL_STAGE;

    /** 被场景遮挡的部分：沿用距离透明度。 */
    public static final Stage DEPTH_OPAQUE_HIDDEN_STAGE = new Stage(true, false, GL11.GL_GREATER);

    /** 可见部分：去掉距离透明度，保持动画包络。 */
    public static final Stage DEPTH_OPAQUE_VISIBLE_STAGE = new Stage(true, false, GL11.GL_LEQUAL, false);

    private ChainPreviewDepthPass() {
    }

    /** @return 通道对应的档位；null 按 XRAY */
    public static Pass select(ChainPreviewDrawPlan.DepthChannel channel) {
        if (channel == ChainPreviewDrawPlan.DepthChannel.OCCLUDE) {
            return Pass.OCCLUDE;
        }
        if (channel == ChainPreviewDrawPlan.DepthChannel.OUTLINE) {
            return Pass.OUTLINE;
        }
        if (channel == ChainPreviewDrawPlan.DepthChannel.DEPTH_OPAQUE) {
            return Pass.DEPTH_OPAQUE;
        }
        return Pass.XRAY;
    }

    /**
     * OUTLINE 外扩宽度关闭时收敛为单遍 XRAY：相同几何不能混合两次，且关闭描边仍须保留穿墙可见性。
     * 其余档位与描边宽度无关。
     *
     * @param channel 深度通道
     * @param outlineWidthPx 描边外扩宽度；非正值或 NaN 表示关闭
     * @return 实际执行档位
     */
    public static Pass resolvePass(ChainPreviewDrawPlan.DepthChannel channel, float outlineWidthPx) {
        Pass pass = select(channel);
        if (pass == Pass.OUTLINE && !(outlineWidthPx > 0.0F)) {
            return Pass.XRAY;
        }
        return pass;
    }

    /** @return 本档绘制次数；null 按 XRAY */
    public static int stageCount(Pass pass) {
        return pass == Pass.OUTLINE || pass == Pass.DEPTH_OPAQUE ? 2 : 1;
    }

    /** @return 对应阶段配方；负索引取首段，超出末段取末段，null 档位按 XRAY */
    public static Stage stage(Pass pass, int index) {
        if (pass == Pass.OCCLUDE) {
            return OCCLUDE_STAGE;
        }
        if (pass == Pass.OUTLINE) {
            return index >= 1 ? OUTLINE_MAIN_STAGE : OUTLINE_SHELL_STAGE;
        }
        if (pass == Pass.DEPTH_OPAQUE) {
            return index >= 1 ? DEPTH_OPAQUE_VISIBLE_STAGE : DEPTH_OPAQUE_HIDDEN_STAGE;
        }
        return XRAY_STAGE;
    }

    /** @return 是否为 OUTLINE 首段外扩壳；负索引按首段 */
    public static boolean isOutlineShellStage(Pass pass, int index) {
        return pass == Pass.OUTLINE && index < 1;
    }
}
