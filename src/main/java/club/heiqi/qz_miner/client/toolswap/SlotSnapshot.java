package club.heiqi.qz_miner.client.toolswap;

/**
 * 槽位角色快照。roleKey 表示可恢复角色，dynamicFingerprint 可包含耐久、NBT 或实例信息。
 */
public final class SlotSnapshot {

    public static final String EMPTY_ROLE_KEY = "qz_miner:empty";

    private final int slot;
    private final String roleKey;
    private final String dynamicFingerprint;

    public SlotSnapshot(int slot, String roleKey, String dynamicFingerprint) {
        if (slot < 0 || slot > 35 || roleKey == null) {
            throw new IllegalArgumentException("slot must be 0..35 and roleKey must not be null");
        }
        this.slot = slot;
        this.roleKey = roleKey;
        this.dynamicFingerprint = dynamicFingerprint == null ? "" : dynamicFingerprint;
    }

    public int slot() {
        return slot;
    }

    public String roleKey() {
        return roleKey;
    }

    public String dynamicFingerprint() {
        return dynamicFingerprint;
    }

    /** @return 是否仍是同一恢复角色；有意忽略耐久、NBT 与对象实例变化 */
    public boolean sameRole(SlotSnapshot other) {
        return other != null && roleKey.equals(other.roleKey);
    }

    /** @return 是否为同一严格内容；用于核对未被使用的原主手角色 */
    public boolean sameContent(SlotSnapshot other) {
        return sameRole(other) && dynamicFingerprint.equals(other.dynamicFingerprint);
    }

    /** @return 是否为空槽角色 */
    public boolean isEmpty() {
        return EMPTY_ROLE_KEY.equals(roleKey);
    }
}
