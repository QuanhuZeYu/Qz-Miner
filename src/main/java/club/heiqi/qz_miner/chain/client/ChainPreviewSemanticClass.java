package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;

/**
 * 连锁预览语义类别（接口冻结 §D 的稳定 id，B2.3 a 部分；本轮按【大模式】重排值域）。
 *
 * <p>类别载体与预览目标同序：由 {@link ChainPreviewState.RenderSnapshot#getSemanticClasses()}
 * 提供，随构建输入交给 ChainPreviewMeshBuilder 写入 aAux.x；着色器按类别从六色 uniform 取色。
 * 值域在接口冻结 §D 冻结，调用方不得自造 id。</p>
 *
 * <p><b>本轮值域变更（用户裁定 B 档：默认颜色按大模式区分）</b>：三个大模式的默认子模式
 * 各自获得稳定 id（{@link #CHAIN_LOCAL}/{@link #AREA_LOCAL}/{@link #INTERACT_LOCAL}），
 * 扩展子模式统一 {@link #SUB_MODE_LOCAL}，其余 id 顺延。旧值域的 0 号类别（三个大模式共用的
 * 「主模式本地预测」）已被这三个 id 取代——它同时服务三个大模式，正是「默认档三模式同色」的根因。</p>
 *
 * <p><b>本轮数据源边界（如实登记）</b>：{@link #TRUNCATED} 需要「被截断目标」这个对象，
 * 但规划到上限即停止、被截断目标不在目标集合内，远端响应被 cap 时客户端也拿不到丢弃数，
 * 因此 a 部分不产出 5——<b>5 是合法 id 但本轮无数据源产出，截断可见性由 B0.5 的
 * ChainPreviewState.getTruncationReason() / getTruncatedCount() 通道表达</b>。
 * {@link #DEFERRED}/{@link #EXECUTED} 按 §D 未启用，同样不产出。
 * 消费者必须仍按合法值处理这三类（不得抛异常）。</p>
 */
public final class ChainPreviewSemanticClass {

    /** CHAIN 大模式的默认子模式（CHAIN_BASE）本地预测。 */
    public static final int CHAIN_LOCAL = 0;
    /** AREA 大模式的默认子模式（AREA_SAME_BLOCK）本地预测。 */
    public static final int AREA_LOCAL = 1;
    /** INTERACT 大模式的默认子模式（INTERACT_BASE）本地预测。 */
    public static final int INTERACT_LOCAL = 2;
    /** 扩展子模式本地预测（含 SPECIAL 两个子模式）。 */
    public static final int SUB_MODE_LOCAL = 3;
    /** 远端预测（远端 provider 返回）。 */
    public static final int REMOTE_PREDICTED = 4;
    /** 因上限被截断的目标；本轮数据源暂不产出（见类注释）。 */
    public static final int TRUNCATED = 5;
    /** 待执行（本轮未启用）。 */
    public static final int DEFERRED = 6;
    /** 已执行（本轮未启用）。 */
    public static final int EXECUTED = 7;
    /** 无法判定；着色器必须兜底 CHAIN 色。 */
    public static final int UNDEFINED = 255;

    private static final int MIN_DEFINED = CHAIN_LOCAL;
    private static final int MAX_DEFINED = EXECUTED;

    private ChainPreviewSemanticClass() {
    }

    /** @return 是否为 §D 冻结的已定义类别（含 6/7，不含 {@link #UNDEFINED}） */
    public static boolean isDefined(int semanticClass) {
        return semanticClass >= MIN_DEFINED && semanticClass <= MAX_DEFINED;
    }

    /** @return 收窄后的类别；非法值一律归为 {@link #UNDEFINED} */
    public static int normalize(int semanticClass) {
        return isDefined(semanticClass) ? semanticClass : UNDEFINED;
    }

    /**
     * 本地预测类别判定（纯函数，供 Controller 与 headless 直连断言）。
     *
     * <p>远端预览由调用方优先判为 {@link #REMOTE_PREDICTED}；本地路径按子模式所属
     * {@link ChainMode} 分派：三个默认子模式（CHAIN_BASE / AREA_SAME_BLOCK / INTERACT_BASE）
     * 各得所属大模式的本地 id，其余扩展子模式（含 SPECIAL 两个，SPECIAL 没有默认子模式）
     * 一律 {@link #SUB_MODE_LOCAL}；null 记 {@link #UNDEFINED}。</p>
     *
     * <p>新增主模式时必须同步 {@link #isPrimarySubMode(ChainSubMode)} 的默认子模式表；
     * 未列入该表的主模式不会得到自己的 id（其子模式统一落 SUB_MODE_LOCAL）。</p>
     *
     * @param subMode 当前子模式，可为 null
     * @return {@link #CHAIN_LOCAL} / {@link #AREA_LOCAL} / {@link #INTERACT_LOCAL}
     *         / {@link #SUB_MODE_LOCAL} / {@link #UNDEFINED}
     */
    public static int resolveLocal(ChainSubMode subMode) {
        if (subMode == null) {
            return UNDEFINED;
        }
        if (!isPrimarySubMode(subMode)) {
            return SUB_MODE_LOCAL;
        }
        return localIdForMode(subMode.getParentMode());
    }

    /**
     * @param mode 主模式，可为 null
     * @return 该主模式默认子模式的本地 id；无专属 id 的主模式（SPECIAL / null）落 {@link #SUB_MODE_LOCAL}
     */
    private static int localIdForMode(ChainMode mode) {
        if (mode == null) {
            return SUB_MODE_LOCAL;
        }
        switch (mode) {
            case CHAIN:
                return CHAIN_LOCAL;
            case AREA:
                return AREA_LOCAL;
            case INTERACT:
                return INTERACT_LOCAL;
            default:
                return SUB_MODE_LOCAL;
        }
    }

    /**
     * @param subMode 子模式
     * @return 是否为所属主模式的默认（未激活扩展）子模式
     */
    public static boolean isPrimarySubMode(ChainSubMode subMode) {
        if (subMode == null) {
            return false;
        }
        switch (subMode) {
            case CHAIN_BASE:
            case AREA_SAME_BLOCK:
            case INTERACT_BASE:
                return true;
            default:
                return false;
        }
    }
}
