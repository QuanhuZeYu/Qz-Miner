package club.heiqi.qz_miner.chain.client.render;

import org.lwjgl.opengl.GL11;

/**
 * 深度分层 pass 选择与状态配方（纯函数 + 纯数据；本类不调用任何 GL）。
 *
 * <p>三档语义（{@link ChainPreviewDrawPlan.DepthChannel}）：</p>
 * <ul>
 *   <li>{@link Pass#XRAY}：单 pass，关深度测试 + depthMask(false) —— 与历史渲染逐字一致（默认档）。</li>
 *   <li>{@link Pass#OCCLUDE}：单 pass，开深度测试 + LEQUAL + depthMask(false) —— 被方块遮挡的条柱不可见。</li>
 *   <li>{@link Pass#OUTLINE}：两 pass —— B3.x 起为「描边壳 pass（关深度测试，沿用历史置顶配方，
 *       全可见）→ 主体 pass（自遮挡正确）」；壳段在顶点阶段外扩 {@code outlineWidthPx}，主体覆盖中心
 *       形成环带。段序固定（壳 → 主体），不引入随机闪烁；XRAY / OCCLUDE 逐字不变。
 *       <b>宽度收敛</b>：{@code outlineWidthPx <= 0} 时壳与主体是同一份几何，两段等于把同一处
 *       混合两次（叠色），故 {@link #resolvePass} 把 OUTLINE 降为单遍 XRAY——见该方法的推导。</li>
 * </ul>
 *
 * <p>混合排序说明（T15 要求 3，B3.x 升级后口径变化一次）：壳段先画、主体后画，可见处主体覆盖壳段中心；
 * 被完全遮挡的条柱呈现「外扩后的实心剪影」（与历史置顶段实心填充同性质，仅宽了外扩量）。</p>
 *
 * <p>pass 只依赖 plan 的 {@code getDepthChannel()}，不改拓扑；settings 引用比较保证 depthMode
 * 改动下一帧生效。</p>
 */
public final class ChainPreviewDepthPass {

    /** 深度分层档位。 */
    public enum Pass {
        XRAY,
        OCCLUDE,
        OUTLINE
    }

    /** 一次绘制的深度状态配方（纯数据，与 renderer 施加的 GL 调用一一对应）。 */
    public static final class Stage {

        private final boolean depthTestEnabled;
        private final boolean depthMaskEnabled;
        private final int depthFunc;

        private Stage(boolean depthTestEnabled, boolean depthMaskEnabled, int depthFunc) {
            this.depthTestEnabled = depthTestEnabled;
            this.depthMaskEnabled = depthMaskEnabled;
            this.depthFunc = depthFunc;
        }

        /** @return 是否开启深度测试 */
        public boolean isDepthTestEnabled() {
            return depthTestEnabled;
        }

        /** @return 是否写入深度 */
        public boolean isDepthMaskEnabled() {
            return depthMaskEnabled;
        }

        /** @return 深度比较函数（仅 depthTestEnabled 时生效） */
        public int getDepthFunc() {
            return depthFunc;
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
                && depthFunc == that.depthFunc;
        }

        @Override
        public int hashCode() {
            int result = depthTestEnabled ? 1 : 0;
            result = 31 * result + (depthMaskEnabled ? 1 : 0);
            result = 31 * result + depthFunc;
            return result;
        }

        @Override
        public String toString() {
            return "Stage{depthTest=" + depthTestEnabled
                + ", depthMask=" + depthMaskEnabled
                + ", depthFunc=0x" + Integer.toHexString(depthFunc)
                + '}';
        }
    }

    /** XRAY：与历史逐字一致的深度状态（关深测 + 不写深度，不设置 depthFunc）。 */
    public static final Stage XRAY_STAGE = new Stage(false, false, GL11.GL_LEQUAL);

    /** OCCLUDE：被方块遮挡不可见（开深测 + LEQUAL + 不写深度）。 */
    public static final Stage OCCLUDE_STAGE = new Stage(true, false, GL11.GL_LEQUAL);

    /** OUTLINE 主体 pass：自遮挡正确（段序末段）。 */
    public static final Stage OUTLINE_MAIN_STAGE = new Stage(true, false, GL11.GL_LEQUAL);

    /**
     * OUTLINE 描边壳 pass（B3.x 段序首段）：沿用历史「置顶」配方（关深测 + 不写深度）—— 全可见。
     */
    public static final Stage OUTLINE_SHELL_STAGE = new Stage(false, false, GL11.GL_LEQUAL);

    /** 历史名：等价于 {@link #OUTLINE_SHELL_STAGE}（B3.x 段序升级后壳段即原置顶段）。 */
    public static final Stage OUTLINE_OVERLAY_STAGE = OUTLINE_SHELL_STAGE;

    private ChainPreviewDepthPass() {
    }

    /**
     * 纯函数：depthChannel → pass；null 兜底 {@link Pass#XRAY}。
     *
     * @param channel plan 的深度通道
     * @return pass 档位，永不为 null
     */
    public static Pass select(ChainPreviewDrawPlan.DepthChannel channel) {
        if (channel == ChainPreviewDrawPlan.DepthChannel.OCCLUDE) {
            return Pass.OCCLUDE;
        }
        if (channel == ChainPreviewDrawPlan.DepthChannel.OUTLINE) {
            return Pass.OUTLINE;
        }
        return Pass.XRAY;
    }

    /**
     * 纯函数：结合真描边宽度决定**实际执行**档。
     *
     * <p>OUTLINE 档相对 XRAY 唯一多出来的就是描边壳段：壳沿面法线外扩 {@code outlineWidthPx}
     * 后才与主体形成环带，两段的几何才不同。宽度为 0（或 NaN / 负值）时无外扩 ⇒ 壳段与主体
     * 是<b>同一份几何</b>，此时仍按 {@link #stageCount} 的 2 段执行，只会让同一处 alpha
     * 混合两次（0.78 → 0.9516），表现为「配置写着 0 = 关闭描边，实际却把预览画得更实」。
     * 故此处收敛为 {@link Pass#XRAY} 单遍：保留 OUTLINE 的「全可见」语义，去掉无意义的第二遍。</p>
     *
     * <p>收敛到 XRAY 而不是 OCCLUDE：调用方选的是 OUTLINE（要穿墙可见性），不是 OCCLUDE；
     * 关掉描边不应顺带把可见性改成「被遮挡即不可见」。</p>
     *
     * <p>{@code !(outlineWidthPx > 0.0F)} 的写法同时覆盖 NaN——NaN 参与 &gt; 比较恒 false，
     * 因此也会收敛为单遍，不会留下两遍叠色的逃逸路径。</p>
     *
     * @param channel        plan 的深度通道
     * @param outlineWidthPx 真描边外扩宽度（物理像素；&lt;= 0 / NaN 视为关闭）
     * @return 实际执行的 pass，永不为 null
     */
    public static Pass resolvePass(ChainPreviewDrawPlan.DepthChannel channel, float outlineWidthPx) {
        Pass pass = select(channel);
        if (pass == Pass.OUTLINE && !(outlineWidthPx > 0.0F)) {
            return Pass.XRAY;
        }
        return pass;
    }

    /**
     * @param pass pass 档位，null 按 {@link Pass#XRAY}
     * @return 本档需要的绘制次数（XRAY / OCCLUDE = 1，OUTLINE = 2）
     */
    public static int stageCount(Pass pass) {
        return pass == Pass.OUTLINE ? 2 : 1;
    }

    /**
     * 取某一 stage 的深度状态配方；越界索引返回该档最后一次绘制的配方（防御，不抛异常）。
     *
     * @param pass  pass 档位，null 按 {@link Pass#XRAY}
     * @param index stage 序号（0 起）
     * @return 状态配方，永不为 null
     */
    public static Stage stage(Pass pass, int index) {
        if (pass == Pass.OCCLUDE) {
            return OCCLUDE_STAGE;
        }
        if (pass == Pass.OUTLINE) {
            return index >= 1 ? OUTLINE_MAIN_STAGE : OUTLINE_SHELL_STAGE;
        }
        return XRAY_STAGE;
    }

    /**
     * 纯函数：某一 stage 是否为 OUTLINE 描边壳段（B3.x 真描边）。
     *
     * <p>越界口径与 {@link #stage} 一致：负索引按首段（壳）处理，index &gt;= 1 为主体段；
     * 非 OUTLINE 档恒 false。</p>
     *
     * @param pass  pass 档位，null 按 {@link Pass#XRAY}
     * @param index stage 序号
     * @return 仅 OUTLINE 档 index &lt; 1 时为 true
     */
    public static boolean isOutlineShellStage(Pass pass, int index) {
        return pass == Pass.OUTLINE && index < 1;
    }
}
