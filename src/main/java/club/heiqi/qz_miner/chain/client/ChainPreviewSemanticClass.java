package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;

/**
 * 预览目标与 aAux.x 共用的稳定语义类别 id，着色器据此选择语义色。
 * 默认子模式按大模式区分，本地扩展与远端预测各用统一类别。
 * TRUNCATED、DEFERRED、EXECUTED 保留为合法分支；截断提示经 ChainPreviewState 的独立信号表达。
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
    /** 截断目标的保留类别。 */
    public static final int TRUNCATED = 5;
    /** 待执行目标的保留类别。 */
    public static final int DEFERRED = 6;
    /** 已执行目标的保留类别。 */
    public static final int EXECUTED = 7;
    /** 未定义类别，着色器使用 CHAIN 色。 */
    public static final int UNDEFINED = 255;

    private static final int MIN_DEFINED = CHAIN_LOCAL;
    private static final int MAX_DEFINED = EXECUTED;

    private ChainPreviewSemanticClass() {
    }

    /** @return 是否为 语义类别表冻结的已定义类别（含 6/7，不含 {@link #UNDEFINED}） */
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
