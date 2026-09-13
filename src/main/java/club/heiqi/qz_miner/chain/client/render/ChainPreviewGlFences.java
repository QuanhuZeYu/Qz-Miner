package club.heiqi.qz_miner.chain.client.render;

import org.lwjgl.opengl.GL11;

/**
 * 预览 GL 状态围栏：状态捕获 / 恢复的唯一入口（T26 / B4.3）。
 *
 * <p>三类围栏集中在本类，renderer 与后端不再各自书写 try / finally 恢复：</p>
 *
 * <ul>
 *   <li>{@link Frame}：帧级状态栈（GL_ALL_ATTRIB_BITS + GL_CLIENT_VERTEX_ARRAY_BIT）与绑定快照，
 *       一次捕获一次恢复；异常路径必恢复（body 抛出、pop 抛出、上下文失效都不影响恢复动作被执行）；
 *       捕获失败不抛给渲染帧，只把原因记入 {@link Frame#getFailure()}；未成功 push 的状态栈不 pop；</li>
 *   <li>{@link Texture}：legacy 淡出乘子的纹理 enable + GL_TEXTURE_BINDING_2D 围栏
 *       （GL_TEXTURE_BINDING_2D 不受 glPushAttrib 覆盖，必须显式恢复到进入前状态）；</li>
 *   <li>{@link #captureBindingsQuietly} / {@link #restoreBindingsQuietly}：帧外入口
 *       （clearMesh / dispose / 后端热切换）的静默围栏，上下文失效不得逃逸渲染帧。</li>
 * </ul>
 *
 * <p>{@link Access} 是唯一 GL 直连点：注入假实现即可在纯 JVM 内断言「一次捕获一次恢复」
 * 「异常路径恢复」「未 push 不 pop」「请求关闭时零 GL 调用」。</p>
 */
public final class ChainPreviewGlFences {

    /** GL 状态访问 seam：绑定 / 状态栈 / 纹理状态（生产实现见 {@link #LWJGL}）。 */
    public interface Access {

        /** @return 绑定快照访问点（3 次 glGetInteger 口径见 {@link ChainPreviewGlBindings}） */
        ChainPreviewGlBindings.Access bindings();

        /** 压入全部属性位。 */
        void pushAllAttribs();

        /** 压入客户端顶点数组属性位。 */
        void pushClientVertexArrayAttribs();

        /** 弹出客户端属性位。 */
        void popClientAttribs();

        /** 弹出全部属性位。 */
        void popAttribs();

        /** @return GL_TEXTURE_2D 是否启用 */
        boolean isTexture2dEnabled();

        /** @return 当前 GL_TEXTURE_BINDING_2D */
        int getTextureBinding2d();

        /** 设置 GL_TEXTURE_2D enable 状态。 */
        void setTexture2dEnabled(boolean enabled);

        /** 绑定 GL_TEXTURE_2D 纹理。 */
        void setTextureBinding2d(int binding);

        /**
         * 消费一次 GL 错误码（T48c-B 诊断用）。
         *
         * <p>默认实现返回 0（GL_NO_ERROR）＝不做检查：既有假实现零破坏。
         * 返回非 0 时由 {@link Frame} 记录「阶段名 + 错误码」并一次性 WARN，
         * 不改变任何控制流语义。</p>
         *
         * @return glGetError 结果；0 = GL_NO_ERROR
         */
        default int consumeGlError() {
            return 0;
        }
    }

    /** LWJGL 实现（渲染线程内使用）。 */
    public static final Access LWJGL = new Access() {

        @Override
        public ChainPreviewGlBindings.Access bindings() {
            return ChainPreviewGlBindings.LWJGL;
        }

        @Override
        public void pushAllAttribs() {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        }

        @Override
        public void pushClientVertexArrayAttribs() {
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        }

        @Override
        public void popClientAttribs() {
            GL11.glPopClientAttrib();
        }

        @Override
        public void popAttribs() {
            GL11.glPopAttrib();
        }

        @Override
        public int consumeGlError() {
            return GL11.glGetError();
        }

        @Override
        public boolean isTexture2dEnabled() {
            return GL11.glGetBoolean(GL11.GL_TEXTURE_2D);
        }

        @Override
        public int getTextureBinding2d() {
            return GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }

        @Override
        public void setTexture2dEnabled(boolean enabled) {
            if (enabled) {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
            } else {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
            }
        }

        @Override
        public void setTextureBinding2d(int binding) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
        }
    };

    /** 一次性围栏诊断：已 WARN 过的阶段（阶段名 → 仅首报）。 */
    private static final java.util.Set<String> FENCE_ERROR_REPORTED_PHASES =
        new java.util.HashSet<String>();
    private static final Object FENCE_ERROR_LOCK = new Object();
    private static String lastFenceErrorPhase = "";
    private static int lastFenceErrorCode;

    private ChainPreviewGlFences() {
    }

    /**
     * 记录一次围栏阶段的 GL 错误（T48c-B）：同一阶段只 WARN 一次，不改语义。
     *
     * @param phase     阶段名（pushAttrib / pushClientAttrib / popClientAttrib / popAttrib）
     * @param errorCode glGetError 结果（0 = 无错误，忽略）
     */
    static void noteFenceGlError(String phase, int errorCode) {
        if (errorCode == 0 || phase == null || phase.isEmpty()) {
            return;
        }
        String message;
        synchronized (FENCE_ERROR_LOCK) {
            lastFenceErrorPhase = phase;
            lastFenceErrorCode = errorCode;
            if (!FENCE_ERROR_REPORTED_PHASES.add(phase)) {
                return;
            }
            message = "[ChainPreview] GL fence " + phase + " reported error "
                + errorCode + " (0x" + Integer.toHexString(errorCode) + ")"
                + "; push/pop semantics unchanged";
        }
        try {
            club.heiqi.qz_miner.MyMod.LOG.warn(message);
        } catch (Throwable ignored) {
            // 日志异常不得影响渲染帧
        }
    }

    /** @return 最近一次观测到错误的围栏阶段名；无则空串（诊断 / 测试用） */
    public static String getLastFenceErrorPhase() {
        synchronized (FENCE_ERROR_LOCK) {
            return lastFenceErrorPhase;
        }
    }

    /** @return 最近一次观测到的 GL 错误码；无则 0（诊断 / 测试用） */
    public static int getLastFenceErrorCode() {
        synchronized (FENCE_ERROR_LOCK) {
            return lastFenceErrorCode;
        }
    }

    /** @return 已一次性上报的阶段数（诊断 / 测试用） */
    public static int getFenceErrorReportCount() {
        synchronized (FENCE_ERROR_LOCK) {
            return FENCE_ERROR_REPORTED_PHASES.size();
        }
    }

    /** 测试用：清空一次性诊断状态（同一阶段可再次上报）。 */
    static void resetFenceErrorDiagnosticsForTest() {
        synchronized (FENCE_ERROR_LOCK) {
            FENCE_ERROR_REPORTED_PHASES.clear();
            lastFenceErrorPhase = "";
            lastFenceErrorCode = 0;
        }
    }

    /** 静默消费一次 GL 错误并记录阶段（异常不得逃逸）。 */
    private static void consumeGlErrorQuietly(Access access, String phase) {
        if (access == null) {
            return;
        }
        try {
            noteFenceGlError(phase, access.consumeGlError());
        } catch (Throwable ignored) {
            // 诊断本身不得影响渲染帧
        }
    }

    /**
     * 安全解析绑定访问点：状态访问点本身在上下文失效时可能抛异常，此处收敛为 null。
     *
     * @param access GL 状态访问点，可为 null
     * @return 绑定访问点；解析失败返回 null（调用方跳过捕获 / 恢复）
     */
    public static ChainPreviewGlBindings.Access bindingsQuietly(ChainPreviewGlFences.Access access) {
        if (access == null) {
            return null;
        }
        try {
            return access.bindings();
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * 帧外静默捕获绑定快照。
     *
     * @param access 绑定访问点，可为 null
     * @return 快照；上下文失效等任何异常时 null（调用方跳过恢复）
     */
    public static ChainPreviewGlBindings captureBindingsQuietly(ChainPreviewGlBindings.Access access) {
        if (access == null) {
            return null;
        }
        try {
            return ChainPreviewGlBindings.capture(access);
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * 帧外静默恢复绑定快照；快照为 null 或恢复失败时静默返回，绝不抛给调用方。
     *
     * @param bindings 快照，可为 null
     * @param access   绑定访问点，可为 null
     */
    public static void restoreBindingsQuietly(
            ChainPreviewGlBindings bindings, ChainPreviewGlBindings.Access access) {
        if (bindings == null || access == null) {
            return;
        }
        try {
            bindings.restore(access);
        } catch (Throwable ignored) {
            // 上下文失效：恢复失败不得逃逸渲染帧
        }
    }

    /**
     * 帧级围栏：捕获绑定快照 + 压入状态栈；{@link #close()} 按相反顺序弹出并恢复绑定，
     * 且不再解析状态访问点（上下文失效不得从 close 逃逸）。
     *
     * <p>与历史 renderer 内联实现的调用序列逐条一致：3 次 glGetInteger → pushAttrib(ALL) →
     * pushClientAttrib(CLIENT_VERTEX_ARRAY) → body → popClientAttrib → popAttrib → 3 次绑定恢复。
     * 差别只在异常路径：任何一步失败都记录 failure 且不抛给渲染帧。</p>
     */
    public static final class Frame implements AutoCloseable {

        private final Access access;
        private final ChainPreviewGlBindings.Access bindingAccess;
        private final ChainPreviewGlBindings bindings;
        private final boolean captured;
        private final boolean pushedAllAttribs;
        private final boolean pushedClientAttribs;
        private final String failure;
        private boolean closed;

        private Frame(
                Access access,
                ChainPreviewGlBindings.Access bindingAccess,
                ChainPreviewGlBindings bindings,
                boolean captured,
                boolean pushedAllAttribs,
                boolean pushedClientAttribs,
                String failure) {
            this.access = access;
            this.bindingAccess = bindingAccess;
            this.bindings = bindings;
            this.captured = captured;
            this.pushedAllAttribs = pushedAllAttribs;
            this.pushedClientAttribs = pushedClientAttribs;
            this.failure = failure == null ? "" : failure;
        }

        /**
         * 打开帧级围栏。
         *
         * @param access      GL 状态访问点
         * @param onCaptured  捕获成功后的回调（帧级捕获计数，可为 null）
         * @return 已压栈的围栏；捕获 / 压栈失败时 failure 非空，仍然保证 close() 配对
         */
        public static Frame open(Access access, Runnable onCaptured) {
            if (access == null) {
                throw new IllegalArgumentException("access");
            }
            ChainPreviewGlBindings.Access bindingAccess = bindingsQuietly(access);
            ChainPreviewGlBindings bindings = null;
            String failure = "";
            if (bindingAccess == null) {
                failure = "bindings access unavailable";
            } else {
                try {
                    bindings = ChainPreviewGlBindings.capture(bindingAccess);
                } catch (Throwable captureFailure) {
                    failure = describe(captureFailure);
                }
            }
            if (bindings != null && onCaptured != null) {
                try {
                    onCaptured.run();
                } catch (Throwable ignored) {
                    // 计数回调异常不得影响渲染帧
                }
            }
            boolean pushedAllAttribs = false;
            boolean pushedClientAttribs = false;
            try {
                access.pushAllAttribs();
                pushedAllAttribs = true;
                // T48c-B：属性栈已删除的上下文会在此报 1282（core-like），一次性 WARN 留证
                consumeGlErrorQuietly(access, "pushAttrib");
            } catch (Throwable pushFailure) {
                if (failure.isEmpty()) {
                    failure = describe(pushFailure);
                }
            }
            if (pushedAllAttribs) {
                try {
                    access.pushClientVertexArrayAttribs();
                    pushedClientAttribs = true;
                    consumeGlErrorQuietly(access, "pushClientAttrib");
                } catch (Throwable pushFailure) {
                    if (failure.isEmpty()) {
                        failure = describe(pushFailure);
                    }
                }
            }
            return new Frame(
                access,
                bindingAccess,
                bindings,
                bindings != null,
                pushedAllAttribs,
                pushedClientAttribs,
                failure);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (pushedClientAttribs) {
                try {
                    access.popClientAttribs();
                    consumeGlErrorQuietly(access, "popClientAttrib");
                } catch (Throwable ignored) {
                    // 上下文失效：状态栈弹出失败不阻断绑定恢复
                }
            }
            if (pushedAllAttribs) {
                try {
                    access.popAttribs();
                    consumeGlErrorQuietly(access, "popAttrib");
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            // 用打开时解析好的访问点恢复：close 内不得再次解析（上下文失效时不得抛）
            restoreBindingsQuietly(bindings, bindingAccess);
        }

        /** @return 绑定快照是否捕获成功 */
        public boolean isCaptured() {
            return captured;
        }

        /** @return 是否已关闭（close 幂等） */
        public boolean isClosed() {
            return closed;
        }

        /** @return 捕获 / 压栈失败原因，无失败为空串 */
        public String getFailure() {
            return failure;
        }
    }

    /**
     * 纹理状态围栏：仅当调用方请求时才捕获纹理 enable + GL_TEXTURE_BINDING_2D，并在 close() 恢复。
     *
     * <p>捕获失败时 {@link #isActive()} 为 false，调用方据此降级为「不施加纹理乘子」，
     * 且不会产生任何恢复调用（不冒险改状态）。</p>
     */
    public static final class Texture implements AutoCloseable {

        private final Access access;
        private final boolean requested;
        private final boolean captured;
        private final boolean previousEnabled;
        private final int previousBinding;
        private final String failure;
        private boolean closed;

        private Texture(
                Access access,
                boolean requested,
                boolean captured,
                boolean previousEnabled,
                int previousBinding,
                String failure) {
            this.access = access;
            this.requested = requested;
            this.captured = captured;
            this.previousEnabled = previousEnabled;
            this.previousBinding = previousBinding;
            this.failure = failure == null ? "" : failure;
        }

        /**
         * 按需捕获纹理状态。
         *
         * @param requested 调用方是否需要纹理状态围栏
         * @param access    GL 状态访问点
         * @return 围栏；未请求或捕获失败时 {@link #isActive()} 为 false
         */
        public static Texture captureIfRequested(boolean requested, Access access) {
            if (access == null) {
                throw new IllegalArgumentException("access");
            }
            if (!requested) {
                return new Texture(access, false, false, false, 0, "");
            }
            try {
                boolean enabled = access.isTexture2dEnabled();
                int binding = access.getTextureBinding2d();
                return new Texture(access, true, true, enabled, binding, "");
            } catch (Throwable captureFailure) {
                return new Texture(access, true, false, false, 0, describe(captureFailure));
            }
        }

        /** @return 是否需要恢复（已请求且捕获成功） */
        public boolean isActive() {
            return requested && captured;
        }

        /** @return 是否请求了围栏但捕获失败（调用方应降级为无纹理乘子） */
        public boolean isCaptureFailed() {
            return requested && !captured;
        }

        /** @return 捕获失败原因，无失败为空串 */
        public String getFailure() {
            return failure;
        }

        /** @return 进入前的 GL_TEXTURE_2D enable 状态（未捕获时 false） */
        public boolean isPreviousEnabled() {
            return previousEnabled;
        }

        /** @return 进入前的 GL_TEXTURE_BINDING_2D（未捕获时 0） */
        public int getPreviousBinding() {
            return previousBinding;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (!captured) {
                return;
            }
            try {
                access.setTexture2dEnabled(previousEnabled);
            } catch (Throwable ignored) {
                // 上下文失效：不得逃逸渲染帧
            }
            try {
                access.setTextureBinding2d(previousBinding);
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    private static String describe(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        return failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
    }
}
