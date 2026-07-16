package club.heiqi.qz_miner.toolswap.protocol;

import java.io.Serializable;

/** 不持有 ItemStack 的不可变槽内容状态。 */
public final class AutoToolSwapStackState implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String roleKey;
    private final AutoToolSwapContentFingerprint contentFingerprint;
    private final boolean empty;
    private final int remainingDurability;

    private AutoToolSwapStackState(String roleKey, AutoToolSwapContentFingerprint contentFingerprint,
            boolean empty, int remainingDurability) {
        if (roleKey == null || roleKey.length() == 0 || contentFingerprint == null || remainingDurability < 0) {
            throw new IllegalArgumentException("stack state contains an invalid role, fingerprint, or durability");
        }
        if (empty) {
            if (!AutoToolSwapProtocol.CANONICAL_EMPTY_ROLE_KEY.equals(roleKey)
                    || !AutoToolSwapContentFingerprint.canonicalEmpty().sameContent(contentFingerprint)
                    || remainingDurability != 0) {
                throw new IllegalArgumentException("empty stack state must use canonical empty values");
            }
        } else if (AutoToolSwapProtocol.CANONICAL_EMPTY_ROLE_KEY.equals(roleKey)) {
            throw new IllegalArgumentException("non-empty stack state must not use the empty role key");
        }
        this.roleKey = roleKey;
        this.contentFingerprint = contentFingerprint;
        this.empty = empty;
        this.remainingDurability = remainingDurability;
    }

    /** 创建非空栈状态。 */
    public static AutoToolSwapStackState occupied(String roleKey,
            AutoToolSwapContentFingerprint contentFingerprint, int remainingDurability) {
        return new AutoToolSwapStackState(roleKey, contentFingerprint, false, remainingDurability);
    }

    /** @return 唯一合法的空槽状态。 */
    public static AutoToolSwapStackState empty() {
        return new AutoToolSwapStackState(AutoToolSwapProtocol.CANONICAL_EMPTY_ROLE_KEY,
                AutoToolSwapContentFingerprint.canonicalEmpty(), true, 0);
    }

    public String roleKey() {
        return roleKey;
    }

    public AutoToolSwapContentFingerprint contentFingerprint() {
        return contentFingerprint;
    }

    public boolean isEmpty() {
        return empty;
    }

    public int remainingDurability() {
        return remainingDurability;
    }

    /** @return 是否是同一严格内容，包含角色、完整指纹、empty 与耐久。 */
    public boolean sameContent(AutoToolSwapStackState other) {
        return other != null && empty == other.empty && remainingDurability == other.remainingDurability
                && roleKey.equals(other.roleKey) && contentFingerprint.sameContent(other.contentFingerprint);
    }

    /**
     * @return 是否仍可视为同一稳定角色；允许数量、耐久、能量与 NBT 变化，空槽只兼容空槽
     */
    public boolean sameRole(AutoToolSwapStackState other) {
        if (other == null || empty != other.empty) {
            return false;
        }
        return empty || roleKey.equals(other.roleKey);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof AutoToolSwapStackState && sameContent((AutoToolSwapStackState) other));
    }

    @Override
    public int hashCode() {
        int value = roleKey.hashCode();
        value = 31 * value + contentFingerprint.hashCode();
        value = 31 * value + (empty ? 1 : 0);
        return 31 * value + remainingDurability;
    }
}
