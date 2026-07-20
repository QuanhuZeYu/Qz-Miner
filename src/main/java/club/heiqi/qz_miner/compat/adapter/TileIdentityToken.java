package club.heiqi.qz_miner.compat.adapter;

import java.util.Objects;

/**
 * TileEntity 身份的不可变纯值令牌。
 *
 * <p>令牌只保存状态与字符串身份，不持有 TileEntity、World、NBT、Class、坐标或反射成员，
 * 因而可从主线程安全传播到规划 worker。</p>
 */
public final class TileIdentityToken {

    /** 身份捕获状态。 */
    public enum State {
        /** 该坐标确认没有 TileEntity。 */
        ABSENT,
        /** 已捕获可比较身份。 */
        PRESENT,
        /** 身份存在但无法可靠读取，必须 fail-closed。 */
        UNRESOLVED
    }

    private static final TileIdentityToken ABSENT = new TileIdentityToken(State.ABSENT, "", "", "");
    private static final TileIdentityToken UNRESOLVED = new TileIdentityToken(State.UNRESOLVED, "", "", "");

    private final State state;
    private final String strategyId;
    private final String typeName;
    private final String identityKey;

    private TileIdentityToken(State state, String strategyId, String typeName, String identityKey) {
        this.state = state;
        this.strategyId = strategyId;
        this.typeName = typeName;
        this.identityKey = identityKey;
    }

    /** @return 已确认无 TileEntity 的共享令牌 */
    public static TileIdentityToken absent() {
        return ABSENT;
    }

    /** @return 身份读取失败的共享令牌 */
    public static TileIdentityToken unresolved() {
        return UNRESOLVED;
    }

    /**
     * 创建已解析身份令牌。
     *
     * @param strategyId 捕获策略稳定标识
     * @param typeName 适配器逻辑类型或未知 TileEntity 的运行时类型名
     * @param identityKey 纯值身份键
     * @return PRESENT 令牌
     */
    public static TileIdentityToken present(String strategyId, String typeName, String identityKey) {
        if (isEmpty(strategyId) || isEmpty(typeName) || identityKey == null) {
            throw new IllegalArgumentException("present tile identity requires strategy, type and key");
        }
        return new TileIdentityToken(State.PRESENT, strategyId, typeName, identityKey);
    }

    /** @return 捕获状态 */
    public State getState() {
        return state;
    }

    /** @return 捕获策略稳定标识 */
    public String getStrategyId() {
        return strategyId;
    }

    /** @return 逻辑类型名 */
    public String getTypeName() {
        return typeName;
    }

    /** @return 纯值身份键；诊断不得输出该字段 */
    public String getIdentityKey() {
        return identityKey;
    }

    /** @return 是否为可比较的 ABSENT/PRESENT 状态 */
    public boolean isResolved() {
        return state != State.UNRESOLVED;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TileIdentityToken)) return false;
        TileIdentityToken that = (TileIdentityToken) other;
        return state == that.state
                && strategyId.equals(that.strategyId)
                && typeName.equals(that.typeName)
                && identityKey.equals(that.identityKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, strategyId, typeName, identityKey);
    }

    @Override
    public String toString() {
        return "TileIdentityToken{" + state + ", strategy=" + strategyId + ", type=" + typeName + "}";
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
