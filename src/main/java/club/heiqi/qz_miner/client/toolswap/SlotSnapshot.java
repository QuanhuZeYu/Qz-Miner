package club.heiqi.qz_miner.client.toolswap;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;

/**
 * 槽位角色快照。roleKey 表示可恢复角色，完整内容指纹用于严格比对。
 */
public final class SlotSnapshot {

    public static final String EMPTY_ROLE_KEY = AutoToolSwapProtocol.CANONICAL_EMPTY_ROLE_KEY;

    private final int slot;
    private final String roleKey;
    private final AutoToolSwapContentFingerprint contentFingerprint;

    /**
     * 兼容旧调用方：由角色和动态内容计算完整严格内容指纹。
     *
     * @param slot 槽位索引
     * @param roleKey 可恢复角色
     * @param dynamicFingerprint 参与严格内容比对的动态内容
     */
    public SlotSnapshot(int slot, String roleKey, String dynamicFingerprint) {
        this(slot, roleKey, AutoToolSwapContentFingerprint.fromContent(
                requireRoleKey(roleKey), dynamicFingerprint == null ? "" : dynamicFingerprint));
    }

    /**
     * 使用协议层同源的完整内容指纹创建快照。
     *
     * @param slot 槽位索引
     * @param roleKey 可恢复角色
     * @param contentFingerprint 完整严格内容指纹
     */
    public SlotSnapshot(int slot, String roleKey, AutoToolSwapContentFingerprint contentFingerprint) {
        if (slot < 0 || slot > 35 || contentFingerprint == null) {
            throw new IllegalArgumentException("slot must be 0..35 and contentFingerprint must not be null");
        }
        this.slot = slot;
        this.roleKey = requireRoleKey(roleKey);
        this.contentFingerprint = contentFingerprint;
    }

    public int slot() {
        return slot;
    }

    public String roleKey() {
        return roleKey;
    }

    /** @return 协议层同源的完整严格内容指纹。 */
    public AutoToolSwapContentFingerprint contentFingerprint() {
        return contentFingerprint;
    }

    /** @return 是否仍是同一恢复角色；有意忽略耐久、NBT 与对象实例变化 */
    public boolean sameRole(SlotSnapshot other) {
        return other != null && roleKey.equals(other.roleKey);
    }

    /** @return 是否为同一严格内容；用于核对未被使用的原主手角色 */
    public boolean sameContent(SlotSnapshot other) {
        return other != null && contentFingerprint.sameContent(other.contentFingerprint);
    }

    /** @return 是否为空槽角色 */
    public boolean isEmpty() {
        return EMPTY_ROLE_KEY.equals(roleKey);
    }

    private static String requireRoleKey(String roleKey) {
        if (roleKey == null || roleKey.length() == 0) {
            throw new IllegalArgumentException("roleKey must not be null or empty");
        }
        if (roleKey.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("roleKey must not contain NUL");
        }
        return roleKey;
    }
}
