package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;

/**
 * 连锁预览语义类别（接口冻结 §D 的稳定 id，B2.3 a 部分）。
 *
 * <p>类别载体与预览目标同序：由 {@link ChainPreviewState.RenderSnapshot#getSemanticClasses()}
 * 提供，随构建输入交给 ChainPreviewMeshBuilder 写入 aAux.x；着色器按类别从四色 uniform 取色。
 * 值域在接口冻结 §D 冻结，调用方不得自造 id。</p>
 *
 * <p><b>本轮数据源边界（如实登记）</b>：{@link #TRUNCATED} 需要「被截断目标」这个对象，
 * 但规划到上限即停止、被截断目标不在目标集合内，远端响应被 cap 时客户端也拿不到丢弃数，
 * 因此 a 部分不产出 3——<b>3 是合法 id 但本轮无数据源产出，截断可见性由 B0.5 的
 * ChainPreviewState.getTruncationReason() / getTruncatedCount() 通道表达</b>。
 * {@link #DEFERRED}/{@link #EXECUTED} 按 §D 未启用，同样不产出。
 * 消费者必须仍按合法值处理这三类（不得抛异常）。</p>
 */
public final class ChainPreviewSemanticClass {

    /** 主模式本地预测（各主模式的默认子模式）。 */
    public static final int PRIMARY_LOCAL = 0;
    /** 子模式本地预测（扩展子模式）。 */
    public static final int SUB_MODE_LOCAL = 1;
    /** 远端预测（远端 provider 返回）。 */
    public static final int REMOTE_PREDICTED = 2;
    /** 因上限被截断的目标；本轮数据源暂不产出（见类注释）。 */
    public static final int TRUNCATED = 3;
    /** 待执行（本轮未启用）。 */
    public static final int DEFERRED = 4;
    /** 已执行（本轮未启用）。 */
    public static final int EXECUTED = 5;
    /** 无法判定；着色器必须兜底主色。 */
    public static final int UNDEFINED = 255;

    private static final int MIN_DEFINED = PRIMARY_LOCAL;
    private static final int MAX_DEFINED = EXECUTED;

    private ChainPreviewSemanticClass() {
    }

    /** @return 是否为 §D 冻结的已定义类别（含 4/5，不含 {@link #UNDEFINED}） */
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
     * <p>远端预览由调用方优先判为 {@link #REMOTE_PREDICTED}；本地路径下，主模式的默认子模式
     * （CHAIN_BASE / AREA_SAME_BLOCK / INTERACT_BASE）记为主模式本地预测，其余扩展子模式记为
     * 子模式本地预测；null 记 {@link #UNDEFINED}。</p>
     *
     * <p>新增主模式时必须同步 {@link #isPrimarySubMode(ChainSubMode)} 的默认子模式表。</p>
     *
     * @param subMode 当前子模式，可为 null
     * @return {@link #PRIMARY_LOCAL} / {@link #SUB_MODE_LOCAL} / {@link #UNDEFINED}
     */
    public static int resolveLocal(ChainSubMode subMode) {
        if (subMode == null) {
            return UNDEFINED;
        }
        return isPrimarySubMode(subMode) ? PRIMARY_LOCAL : SUB_MODE_LOCAL;
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
