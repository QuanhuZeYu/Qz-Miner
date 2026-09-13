package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.config.CommittedSnapshot;
import club.heiqi.qz_miner.config.ConfigBootstrap;

/**
 * 预览输入版本化快照（B1.2 / task-20）。
 *
 * <p>把「世界 / 目标 / 命中面 / 模式 / 子模式 / 半径 / 上限 / 视觉设置（含 barThickness、depthMode、
 * colorSource、fade、alpha）/ 配置 revision / 对象组 revision」收进一个不可变值对象；
 * 预览是否需要换 generation 退化为「快照不相等即换」。相等即无需重建。</p>
 *
 * <h3>零分配与热路径契约</h3>
 * <ul>
 *   <li>{@link #differsInSemanticIdentity(PreviewInputSnapshot)} /
 *       {@link #hasSameTarget(PreviewInputSnapshot)} / {@link #equals(Object)} 均不分配。</li>
 *   <li>生产调用方先做无分配判定，只有确实需要换 generation 时才构造新快照
 *       （每 tick 不产生垃圾）。</li>
 *   <li>视觉设置按 {@code equals} 值比较：1 Hz 重建但内容相同的快照引用变化<b>不</b>触发重建；
 *       内容变化（配置热改）才触发。</li>
 * </ul>
 *
 * <h3>语义维度</h3>
 * <p>world / face / mode / subMode / radius / maxTargets / visualSettings / configRevision /
 * objectGroupRevision 任一变化属于「语义变化」，必须立即换 generation（不得走入去抖）；
 * 仅 target 变化时才允许按 {@link ChainPreviewController} 的 2 tick 稳定窗口去抖。</p>
 */
public final class PreviewInputSnapshot {

    private final Object world;
    private final ChainTarget target;
    private final int concreteFace;
    private final ChainMode mode;
    private final ChainSubMode subMode;
    private final int radius;
    private final int maxTargets;
    private final ChainPreviewVisualSettings visualSettings;
    private final long configRevision;
    private final long objectGroupRevision;

    /**
     * 显式构造（生产侧由 ChainPreviewController 采样后构造）。
     *
     * @param world 世界引用身份（引用比较，null = 无世界）
     * @param target 当前瞄准目标
     * @param concreteFace 归一化命中面
     * @param mode 主模式
     * @param subMode 子模式
     * @param radius 生效预览半径
     * @param maxTargets 生效预览上限
     * @param visualSettings 当前视觉设置快照（值比较）
     * @param configRevision 配置 revision（0 = 未接线）
     * @param objectGroupRevision 对象组 revision（0 = 无）
     */
    public PreviewInputSnapshot(
            Object world,
            ChainTarget target,
            int concreteFace,
            ChainMode mode,
            ChainSubMode subMode,
            int radius,
            int maxTargets,
            ChainPreviewVisualSettings visualSettings,
            long configRevision,
            long objectGroupRevision) {
        this.world = world;
        this.target = target;
        this.concreteFace = concreteFace;
        this.mode = mode;
        this.subMode = subMode;
        this.radius = radius;
        this.maxTargets = maxTargets;
        this.visualSettings = visualSettings;
        this.configRevision = configRevision;
        this.objectGroupRevision = objectGroupRevision;
    }

    /** @return 世界引用（引用身份） */
    public Object getWorld() {
        return world;
    }

    /** @return 当前瞄准目标 */
    public ChainTarget getTarget() {
        return target;
    }

    /** @return 归一化命中面 */
    public int getConcreteFace() {
        return concreteFace;
    }

    /** @return 主模式 */
    public ChainMode getMode() {
        return mode;
    }

    /** @return 子模式 */
    public ChainSubMode getSubMode() {
        return subMode;
    }

    /** @return 生效预览半径 */
    public int getRadius() {
        return radius;
    }

    /** @return 生效预览上限 */
    public int getMaxTargets() {
        return maxTargets;
    }

    /** @return 视觉设置快照（值比较） */
    public ChainPreviewVisualSettings getVisualSettings() {
        return visualSettings;
    }

    /** @return 配置 revision（0 = 未接线） */
    public long getConfigRevision() {
        return configRevision;
    }

    /** @return 对象组 revision */
    public long getObjectGroupRevision() {
        return objectGroupRevision;
    }

    /**
     * @param other 候选快照
     * @return 除 target 外的任一语义维度是否不同（语义变化必须立即换 generation）
     */
    public boolean differsInSemanticIdentity(PreviewInputSnapshot other) {
        if (other == null) {
            return true;
        }
        return world != other.world
            || concreteFace != other.concreteFace
            || mode != other.mode
            || subMode != other.subMode
            || radius != other.radius
            || maxTargets != other.maxTargets
            || configRevision != other.configRevision
            || objectGroupRevision != other.objectGroupRevision
            || !settingsEquals(visualSettings, other.visualSettings);
    }

    /**
     * @param other 候选快照
     * @return 两侧 target 相等（两侧均为 null 视为相同目标）；null 候选快照返回 false（保守）
     */
    public boolean hasSameTarget(PreviewInputSnapshot other) {
        return other != null && targetEquals(target, other.target);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PreviewInputSnapshot)) {
            return false;
        }
        PreviewInputSnapshot that = (PreviewInputSnapshot) other;
        return world == that.world
            && concreteFace == that.concreteFace
            && radius == that.radius
            && maxTargets == that.maxTargets
            && configRevision == that.configRevision
            && objectGroupRevision == that.objectGroupRevision
            && mode == that.mode
            && subMode == that.subMode
            && targetEquals(target, that.target)
            && settingsEquals(visualSettings, that.visualSettings);
    }

    @Override
    public int hashCode() {
        int result = System.identityHashCode(world);
        result = 31 * result + (target == null ? 0 : target.hashCode());
        result = 31 * result + concreteFace;
        result = 31 * result + (mode == null ? 0 : mode.hashCode());
        result = 31 * result + (subMode == null ? 0 : subMode.hashCode());
        result = 31 * result + radius;
        result = 31 * result + maxTargets;
        result = 31 * result + (visualSettings == null ? 0 : visualSettings.hashCode());
        result = 31 * result + (int) (configRevision ^ (configRevision >>> 32));
        result = 31 * result + (int) (objectGroupRevision ^ (objectGroupRevision >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "PreviewInputSnapshot{world=" + System.identityHashCode(world)
            + ", target=" + target
            + ", face=" + concreteFace
            + ", mode=" + mode
            + ", subMode=" + subMode
            + ", radius=" + radius
            + ", maxTargets=" + maxTargets
            + ", settings=" + visualSettings
            + ", configRev=" + configRevision
            + ", objectGroupRev=" + objectGroupRevision
            + '}';
    }

    /**
     * 无分配比较：除 target 外的语义维度是否与当前快照不同。
     *
     * @param current 当前已接受快照；null 视为"需要重建"
     * @param world 本 tick 世界引用
     * @param concreteFace 本 tick 命中面
     * @param mode 本 tick 主模式
     * @param subMode 本 tick 子模式
     * @param radius 本 tick 生效半径
     * @param maxTargets 本 tick 生效上限
     * @param visualSettings 本 tick 视觉设置快照
     * @param configRevision 本 tick 配置 revision
     * @param objectGroupRevision 本 tick 对象组 revision
     * @return 是否需要立即换 generation（语义变化不走抖动窗口）
     */
    public static boolean semanticIdentityDiffers(
            PreviewInputSnapshot current,
            Object world,
            int concreteFace,
            ChainMode mode,
            ChainSubMode subMode,
            int radius,
            int maxTargets,
            ChainPreviewVisualSettings visualSettings,
            long configRevision,
            long objectGroupRevision) {
        if (current == null) {
            return true;
        }
        return current.world != world
            || current.concreteFace != concreteFace
            || current.mode != mode
            || current.subMode != subMode
            || current.radius != radius
            || current.maxTargets != maxTargets
            || current.configRevision != configRevision
            || current.objectGroupRevision != objectGroupRevision
            || !settingsEquals(current.visualSettings, visualSettings);
    }

    /**
     * 无分配比较：target 是否与当前快照不同。
     *
     * @param current 当前已接受快照；null 视为不同
     * @param target 本 tick 目标
     * @return 是否不同
     */
    public static boolean targetDiffers(PreviewInputSnapshot current, ChainTarget target) {
        return current == null || !targetEquals(current.target, target);
    }

    /** @return 当前配置 revision（ConfigBootstrap 提交 epoch；无提交为 0） */
    public static long currentConfigRevision() {
        CommittedSnapshot committed = ConfigBootstrap.currentCommittedSnapshot();
        return committed == null ? 0L : committed.epoch;
    }

    /** @return 当前对象组 revision（客户端状态未初始化时为 0） */
    public static long currentObjectGroupRevision() {
        return MyMod.chainStateService == null
            ? 0L
            : MyMod.chainStateService.getClientState().getServerObjectGroupRevision();
    }

    private static boolean targetEquals(ChainTarget a, ChainTarget b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean settingsEquals(
            ChainPreviewVisualSettings a, ChainPreviewVisualSettings b) {
        return a == b || (a != null && a.equals(b));
    }
}
